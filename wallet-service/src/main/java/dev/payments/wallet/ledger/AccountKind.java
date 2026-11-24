package dev.payments.wallet.ledger;

/** A wallet belongs to a customer; a system account belongs to us. */
public enum AccountKind {
    WALLET, SYSTEM;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static AccountKind fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
