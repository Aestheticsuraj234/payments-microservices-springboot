package dev.payments.transfer.saga;

import dev.payments.transfer.TransferProperties;
import dev.payments.transfer.error.FieldErrors;
import dev.payments.transfer.error.IdempotencyException;
import dev.payments.transfer.error.NotFoundException;
import dev.payments.transfer.error.UnavailableException;
import dev.payments.transfer.wallet.WalletClient;
import dev.payments.transfer.wallet.WalletClient.WalletInfo;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Accepts transfers. It checks what it can up front, records the transfer as
 * {@code pending}, and returns at once: the {@link SagaOrchestrator} moves the
 * money afterwards. The client polls {@code GET /v1/transfers/{id}} to see how
 * it ends.
 */
@Service
public class TransferService {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** A new transfer, or an earlier one returned again for the same idempotency key. */
    public record Accepted(Transfer transfer, boolean replayed) {
    }

    private final TransferRepository transfers;
    private final WalletClient wallets;
    private final TransferProperties properties;

    public TransferService(TransferRepository transfers, WalletClient wallets, TransferProperties properties) {
        this.transfers = transfers;
        this.wallets = wallets;
        this.properties = properties;
    }

    public Accepted create(UUID user, @Nullable String key, @Nullable String from, @Nullable String to,
            long amount, @Nullable String currency) {
        if (key == null || key.isEmpty() || key.length() > 255) {
            throw new IdempotencyException(IdempotencyException.Reason.KEY_REQUIRED);
        }
        // The key is a column with a unique constraint: the transfer row is
        // its own idempotency record. No separate table needed.
        Optional<Transfer> earlier = transfers.findByKey(user, key);
        if (earlier.isPresent()) {
            return replay(earlier.get(), from, to, amount, currency);
        }

        var errors = new FieldErrors();
        if (amount <= 0) {
            errors.add("amount", "must be a positive number of minor units, like 1050 for 10.50");
        } else if (amount > properties.maxAmount()) {
            errors.add("amount", "must be at most " + properties.maxAmount());
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            errors.add("currency", "must be a three-letter ISO 4217 code, like USD");
        }
        WalletInfo source = lookUp(errors, "from", from);
        WalletInfo target = lookUp(errors, "to", to);
        if (source != null && !source.ownerId().equals(user)) {
            errors.add("from", "no such wallet"); // someone else's wallet: as if it didn't exist
        }
        if (source != null && target != null && !errors.has("from")) {
            if (source.id().equals(target.id())) {
                errors.add("to", "must be a different wallet");
            } else if (!target.currency().equals(source.currency())) {
                errors.add("to", "must be a " + source.currency() + " wallet, like from");
            } else if (!errors.has("currency") && !source.currency().equals(currency)) {
                errors.add("currency", "must match the wallets' currency, " + source.currency());
            }
        }
        errors.throwIfAny();

        return transfers.create(user, key, source.id(), target.id(), amount, currency)
                .map(t -> new Accepted(t, false))
                // Another request with the same key won the race: answer like a retry.
                .orElseGet(() -> replay(transfers.findByKey(user, key).orElseThrow(), from, to, amount, currency));
    }

    public Transfer get(UUID user, UUID id) {
        return transfers.find(id)
                .filter(t -> t.userId().equals(user))
                .orElseThrow(() -> new NotFoundException("transfer not found"));
    }

    /** The same key must come with the same request. */
    private static Accepted replay(Transfer t, @Nullable String from, @Nullable String to, long amount,
            @Nullable String currency) {
        boolean same = t.fromWalletId().toString().equals(from) && t.toWalletId().toString().equals(to)
                && t.amount() == amount && t.currency().equals(currency);
        if (!same) {
            throw new IdempotencyException(IdempotencyException.Reason.KEY_REUSED);
        }
        return new Accepted(t, true);
    }

    /**
     * Asks the wallet service about a wallet. This is a synchronous call on
     * the request path: if the wallet service is down, we can't accept
     * transfers. That's a deliberate choice: better to say "try later" than to
     * accept a transfer we can't check.
     */
    private @Nullable WalletInfo lookUp(FieldErrors errors, String field, @Nullable String id) {
        UUID walletId;
        try {
            walletId = UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            errors.add(field, "must be a wallet ID");
            return null;
        }
        try {
            return wallets.wallet(walletId);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                errors.add(field, "no such wallet");
                return null;
            }
            throw new UnavailableException("the wallet service refused the lookup");
        } catch (RestClientException e) {
            throw new UnavailableException("the wallet service is not available, try again later");
        }
    }
}
