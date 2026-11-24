package dev.payments.wallet.error;

/**
 * The request was valid, but breaks a business rule, like spending more than a
 * wallet holds. {@link #code()} is a stable reason, like
 * {@code insufficient_funds}, that clients can branch on; the message is for
 * humans and may change.
 */
public final class RuleViolationException extends PaymentsException {

    private final String code;

    public RuleViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
