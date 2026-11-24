package dev.payments.wallet.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.payments.wallet.error.FieldErrors;
import dev.payments.wallet.error.NotFoundException;
import dev.payments.wallet.money.Money;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The wallet API over HTTP. It only translates: JSON and URLs in, calls to the
 * {@link WalletService}, JSON out. The rules live in the service.
 *
 * <p>The request and response records below are the API's contract with our
 * clients. They are separate from the domain records on purpose: the domain
 * can change shape without breaking a client.
 */
@RestController
@RequestMapping("/v1/wallets")
public class WalletController {

    record CreateWalletRequest(@Nullable String ownerId, @Nullable String currency) {
    }

    record WalletResponse(UUID id, UUID ownerId, String currency, Instant createdAt) {
        static WalletResponse of(Account a) {
            return new WalletResponse(a.id(), a.ownerId(), a.currency(), a.createdAt());
        }
    }

    record BalanceResponse(UUID walletId, Money balance) {
    }

    record DepositResponse(UUID transactionId, UUID walletId, Money amount, Instant createdAt) {
    }

    record EntryResponse(long id, UUID transactionId, Direction direction, Money amount, Instant createdAt) {
        static EntryResponse of(Entry e) {
            return new EntryResponse(e.id(), e.transactionId(), e.direction(),
                    new Money(e.amount(), e.currency()), e.createdAt());
        }
    }

    record EntriesResponse(
            List<EntryResponse> entries,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String nextCursor) {
    }

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @PostMapping
    ResponseEntity<WalletResponse> create(@RequestBody CreateWalletRequest request) {
        UUID ownerId = null;
        try {
            ownerId = request.ownerId() == null ? null : UUID.fromString(request.ownerId());
        } catch (IllegalArgumentException e) {
            new FieldErrors().add("owner_id", "must be a UUID").throwIfAny();
        }
        Account wallet = wallets.createWallet(ownerId, request.currency());
        return ResponseEntity.created(URI.create("/v1/wallets/" + wallet.id()))
                .body(WalletResponse.of(wallet));
    }

    @GetMapping("/{id}")
    WalletResponse get(@PathVariable String id) {
        return WalletResponse.of(wallets.wallet(walletId(id)));
    }

    @GetMapping("/{id}/balance")
    BalanceResponse balance(@PathVariable String id) {
        UUID walletId = walletId(id);
        return new BalanceResponse(walletId, wallets.balance(walletId));
    }

    @PostMapping("/{id}/deposits")
    ResponseEntity<DepositResponse> deposit(@PathVariable String id, @RequestBody Money amount) {
        UUID walletId = walletId(id);
        Transaction tx = wallets.deposit(walletId, amount);
        return ResponseEntity.status(201).body(new DepositResponse(tx.id(), walletId, amount, tx.createdAt()));
    }

    @GetMapping("/{id}/entries")
    EntriesResponse entries(
            @PathVariable String id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        EntryPage page = wallets.entries(walletId(id), cursor, limit);
        return new EntriesResponse(page.entries().stream().map(EntryResponse::of).toList(), page.nextCursor());
    }

    /** An id that isn't a UUID can't name a wallet: a 404, just like an unknown one. */
    static UUID walletId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("wallet not found");
        }
    }
}
