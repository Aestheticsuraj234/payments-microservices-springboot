package dev.payments.wallet.ledger;

import dev.payments.wallet.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    public TransferController(WalletService wallets) {
        this.wallets = wallets;
    }

    @PostMapping("/v1/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    TransferResponse transfer(@RequestBody TransferRequest request) {
        return TransferResponse.of(wallets.transfer(
                request.from(), request.to(), new Money(request.amount(), request.currency())));
    }
}
