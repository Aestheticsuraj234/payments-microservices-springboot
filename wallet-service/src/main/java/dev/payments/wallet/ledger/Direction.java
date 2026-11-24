package dev.payments.wallet.ledger;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The two sides of every ledger entry.
 *
 * <p>Each account has a <em>normal balance</em>: the side that makes it grow.
 * A customer's wallet is money we owe them, a liability, so a credit grows it.
 * Our settlement account is money we hold at the bank, an asset, so a debit
 * grows it. A deposit debits settlement and credits the wallet: both grow, and
 * the books still balance.
 */
public enum Direction {
    DEBIT, CREDIT;

    /** The value in the database and in JSON: {@code debit} or {@code credit}. */
    @JsonValue
    public String dbValue() {
        return name().toLowerCase();
    }

    public static Direction fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
