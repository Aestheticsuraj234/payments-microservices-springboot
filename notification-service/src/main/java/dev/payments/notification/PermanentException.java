package dev.payments.notification;

/**
 * A message that will never work, however often we retry it: it doesn't
 * parse, or it names a user who doesn't exist. It goes straight to the
 * dead-letter topic instead of being retried.
 */
public class PermanentException extends RuntimeException {

    public PermanentException(String message, Throwable cause) {
        super(message, cause);
    }

    public PermanentException(String message) {
        super(message);
    }
}
