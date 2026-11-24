package dev.payments.transfer.error;

/** The request has no authenticated caller: it didn't come through the gateway. */
public final class UnauthenticatedException extends PaymentsException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}
