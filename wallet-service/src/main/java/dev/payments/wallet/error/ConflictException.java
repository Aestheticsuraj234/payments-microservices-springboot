package dev.payments.wallet.error;

/** The request clashes with the current state, like creating something that already exists. */
public final class ConflictException extends PaymentsException {

    public ConflictException(String message) {
        super(message);
    }
}
