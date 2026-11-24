package dev.payments.wallet.ledger;

import java.time.Instant;
import java.util.UUID;

/** One line of a transaction: an amount debited or credited to one account. */
public record Entry(
        long id,
        UUID transactionId,
        UUID accountId,
        Direction direction,
        long amount,
        String currency,
        Instant createdAt) {
}
