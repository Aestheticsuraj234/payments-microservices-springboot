package dev.payments.wallet.error;

/** The requested resource doesn't exist, or the caller may not know that it does. */
public final class NotFoundException extends PaymentsException {

    public NotFoundException(String message) {
        super(message);
    }
}
