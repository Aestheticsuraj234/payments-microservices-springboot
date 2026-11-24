package dev.payments.wallet.ledger;

import java.util.UUID;

/** An entry we want to record: money in or out of one account. */
public record Posting(UUID accountId, Direction direction, long amount, String currency) {

    public static Posting debit(Account account, long amount) {
        return new Posting(account.id(), Direction.DEBIT, amount, account.currency());
    }

    public static Posting credit(Account account, long amount) {
        return new Posting(account.id(), Direction.CREDIT, amount, account.currency());
    }
}
