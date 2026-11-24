package dev.payments.wallet.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.payments.wallet.error.NotFoundException;
import dev.payments.wallet.idempotency.IdempotentRequests;
import dev.payments.wallet.money.Money;
import dev.payments.wallet.web.Caller;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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

    record CreateWalletRequest(@Nullable String currency) {
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
    private final IdempotentRequests idempotent;

    public WalletController(WalletService wallets, IdempotentRequests idempotent) {
        this.wallets = wallets;
        this.idempotent = idempotent;
    }

    /** Opens a wallet for the caller: the owner comes from the token, never from the body. */
    @PostMapping
    ResponseEntity<WalletResponse> create(Caller caller, @RequestBody CreateWalletRequest request) {
        Account wallet = wallets.createWallet(caller.userId(), request.currency());
        return ResponseEntity.created(URI.create("/v1/wallets/" + wallet.id()))
                .body(WalletResponse.of(wallet));
    }

    @GetMapping("/{id}")
    WalletResponse get(Caller caller, @PathVariable String id) {
        return WalletResponse.of(wallets.wallet(caller.userId(), walletId(id)));
    }

    @GetMapping("/{id}/balance")
    BalanceResponse balance(Caller caller, @PathVariable String id) {
        UUID walletId = walletId(id);
        return new BalanceResponse(walletId, wallets.balance(caller.userId(), walletId));
    }

    /** Moves money, so it runs once per {@code Idempotency-Key}: a retry gets the first response back. */
    @PostMapping("/{id}/deposits")
    ResponseEntity<byte[]> deposit(Caller caller, @PathVariable String id, @RequestBody Money amount,
            HttpServletRequest request) {
        UUID walletId = walletId(id);
        return idempotent.run(caller, request, amount, HttpStatus.CREATED, () -> {
            Transaction tx = wallets.deposit(caller.userId(), walletId, amount);
            return new DepositResponse(tx.id(), walletId, amount, tx.createdAt());
        });
    }

    @GetMapping("/{id}/entries")
    EntriesResponse entries(
            Caller caller,
            @PathVariable String id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        EntryPage page = wallets.entries(caller.userId(), walletId(id), cursor, limit);
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
