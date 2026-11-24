package dev.payments.wallet.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A posted journal entry, together with its entries. */
public record Transaction(UUID id, String description, Instant createdAt, List<Entry> entries) {
}
