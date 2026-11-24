package dev.payments.wallet.ledger;

import dev.payments.wallet.idempotency.IdempotentRequests;
import dev.payments.wallet.money.Money;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Moves money between wallets: {@code POST /v1/transfers}. */
@RestController
public class TransferController {

    record TransferRequest(@Nullable String from, @Nullable String to, long amount, @Nullable String currency) {
    }

    record TransferResponse(UUID transactionId, UUID from, UUID to, Money amount, Instant createdAt) {
        static TransferResponse of(Transfer t) {
            return new TransferResponse(t.transactionId(), t.from(), t.to(), t.amount(), t.createdAt());
        }
    }

    private final WalletService wallets;
    private final IdempotentRequests idempotent;

    public TransferController(WalletService wallets, IdempotentRequests idempotent) {
        this.wallets = wallets;
        this.idempotent = idempotent;
    }

    /** Moves money, so it runs once per {@code Idempotency-Key}. */
    @PostMapping("/v1/transfers")
    ResponseEntity<byte[]> transfer(@RequestBody TransferRequest body, HttpServletRequest request) {
        return idempotent.run(request, body, HttpStatus.CREATED, () -> TransferResponse.of(wallets.transfer(
                body.from(), body.to(), new Money(body.amount(), body.currency()))));
    }
}
