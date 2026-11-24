package dev.payments.wallet.ledger;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** One page of a wallet's entries, newest first. {@code nextCursor} is null on the last page. */
public record EntryPage(List<Entry> entries, @Nullable String nextCursor) {
}
