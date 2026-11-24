package dev.payments.wallet.ledger;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Anything that holds money: a customer's wallet, or one of our system accounts. */
public record Account(
        UUID id,
        AccountKind kind,
        @Nullable UUID ownerId,
        String name,
        String currency,
        Direction normalBalance,
        Instant createdAt) {

    public boolean isWallet() {
        return kind == AccountKind.WALLET;
    }
}
