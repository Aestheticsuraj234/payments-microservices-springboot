package dev.payments.wallet.ledger;

import dev.payments.wallet.money.Money;
import java.time.Instant;
import java.util.UUID;

/** Money that moved from one wallet to another, as one balanced transaction. */
public record Transfer(UUID transactionId, UUID from, UUID to, Money amount, Instant createdAt) {
}
