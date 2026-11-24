package dev.payments.transfer.error;

/** A request can't run with the idempotency key it sent. {@link #reason()} says why. */
public final class IdempotencyException extends PaymentsException {

    public enum Reason {
        /** No key, or one longer than 255 characters. */
        KEY_REQUIRED("idempotency_key_required",
                "requests that move money need an Idempotency-Key header, of 1 to 255 characters"),
        /** The key was used before, for a different request. */
        KEY_REUSED("idempotency_key_reused",
                "this Idempotency-Key was already used for a different request");

        private final String code;
        private final String message;

        Reason(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String code() {
            return code;
        }
    }

    private final Reason reason;

    public IdempotencyException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
