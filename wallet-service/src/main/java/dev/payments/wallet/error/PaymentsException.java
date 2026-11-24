package dev.payments.wallet.error;

/**
 * The errors our business logic throws. They say <em>what</em> went wrong, not
 * how to report it: {@link dev.payments.wallet.web.ApiExceptionHandler} is the
 * only place that turns them into HTTP responses.
 *
 * <p>The hierarchy is {@code sealed}: the compiler knows every subclass, so the
 * handler's {@code switch} must cover them all, and adding a new kind of error
 * without deciding its HTTP status is a compile error.
 */
public sealed abstract class PaymentsException extends RuntimeException
        permits NotFoundException, ConflictException, ValidationException, RuleViolationException,
        IdempotencyException, UnauthenticatedException {

    protected PaymentsException(String message) {
        super(message);
    }
}
