package dev.payments.transfer.error;

/** A service we depend on didn't answer in time. Retrying later may work: a 503. */
public final class UnavailableException extends PaymentsException {

    public UnavailableException(String message) {
        super(message);
    }
}
