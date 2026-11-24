package dev.payments.transfer.saga;

import java.time.Instant;
import java.util.UUID;

/** A transfer and where its saga is. {@code version} changes with every write. */
public record Transfer(
        UUID id,
        UUID userId,
        String idempotencyKey,
        UUID fromWalletId,
        UUID toWalletId,
        long amount,
        String currency,
        State state,
        String failureReason,
        int attempts,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
