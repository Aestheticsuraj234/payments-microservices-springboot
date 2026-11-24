package dev.payments.wallet.holds;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Money set aside from a wallet for a transfer, until it is captured or released. */
public record Hold(UUID id, UUID walletId, long amount, String currency, Status status, @Nullable UUID settledTo) {

    public enum Status {
        HELD, CAPTURED, RELEASED;

        String dbValue() {
            return name().toLowerCase();
        }

        static Status fromDb(String value) {
            return valueOf(value.toUpperCase());
        }
    }
}
