package dev.payments.transfer.saga;

import java.util.List;
import java.util.Map;

/**
 * Where a transfer is in its saga.
 *
 * <pre>
 *   PENDING ──hold ok──▶ FUNDS_HELD ──capture ok──▶ COMPLETED
 *      │                     │
 *  hold refused         capture refused
 *      ▼                     ▼
 *    FAILED            COMPENSATING ──release ok──▶ REVERSED
 * </pre>
 */
public enum State {
    /** Accepted: nothing has moved yet. */
    PENDING,
    /** The money left the sender, into a hold. */
    FUNDS_HELD,
    /** The receiver has it. */
    COMPLETED,
    /** A later step was refused: giving the hold back. */
    COMPENSATING,
    /** Refused before anything moved. */
    FAILED,
    /** The hold went back to the sender. */
    REVERSED;

    private static final Map<State, List<State>> TRANSITIONS = Map.of(
            PENDING, List.of(FUNDS_HELD, FAILED),
            FUNDS_HELD, List.of(COMPLETED, COMPENSATING),
            COMPENSATING, List.of(REVERSED));

    public boolean canBecome(State next) {
        return TRANSITIONS.getOrDefault(this, List.of()).contains(next);
    }

    /** True when the saga is over and nothing will ever change again. */
    public boolean isFinal() {
        return !TRANSITIONS.containsKey(this);
    }

    public String dbValue() {
        return name().toLowerCase();
    }

    public static State fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
