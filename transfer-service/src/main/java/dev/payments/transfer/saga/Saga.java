package dev.payments.transfer.saga;

import org.jspecify.annotations.Nullable;

/**
 * The saga, as data: in each unfinished state, the step to run, where success
 * leads, and where a refusal leads. Keeping it in one small table makes the
 * whole flow reviewable at a glance, and testable without any I/O.
 */
public final class Saga {

    public enum Step { HOLD, CAPTURE, RELEASE }

    /**
     * @param step     what to ask the wallet for
     * @param next     where success leads
     * @param rejected where a refusal leads; null means "never give up": a
     *                 release must eventually succeed, or money stays stuck
     */
    public record Move(Step step, State next, @Nullable State rejected) {
    }

    private Saga() {
    }

    public static Move moveFrom(State state) {
        return switch (state) {
            case PENDING -> new Move(Step.HOLD, State.FUNDS_HELD, State.FAILED);
            case FUNDS_HELD -> new Move(Step.CAPTURE, State.COMPLETED, State.COMPENSATING);
            case COMPENSATING -> new Move(Step.RELEASE, State.REVERSED, null);
            case COMPLETED, FAILED, REVERSED -> throw new IllegalStateException(state + " is final");
        };
    }
}
