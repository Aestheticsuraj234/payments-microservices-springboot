package dev.payments.auth.error;

/**
 * The caller didn't prove who they are: a wrong password, or a token that's
 * missing, invalid, expired or revoked. The message says as little as
 * possible, so it can't help anyone guess.
 */
public final class UnauthenticatedException extends PaymentsException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}
