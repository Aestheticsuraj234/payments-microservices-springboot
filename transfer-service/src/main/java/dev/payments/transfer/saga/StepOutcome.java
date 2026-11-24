package dev.payments.transfer.saga;

/**
 * How a step went. The distinction between the last two is the most
 * important idea in this service:
 *
 * <ul>
 *   <li>{@link Rejected}: the wallet answered "no" (insufficient funds). It
 *       will say no again: don't retry, take the saga's other branch.</li>
 *   <li>{@link Failed}: we don't know what happened (a timeout, a 503, a
 *       dropped connection). The step may or may not have run. Retry the
 *       same, idempotent call until we get a real answer.</li>
 * </ul>
 */
public sealed interface StepOutcome {

    record Succeeded() implements StepOutcome {
    }

    record Rejected(String reason) implements StepOutcome {
    }

    record Failed(String detail) implements StepOutcome {
    }
}
