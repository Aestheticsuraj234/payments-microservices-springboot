package dev.payments.wallet.web;

import dev.payments.wallet.error.ConflictException;
import dev.payments.wallet.error.IdempotencyException;
import dev.payments.wallet.error.NotFoundException;
import dev.payments.wallet.error.PaymentsException;
import dev.payments.wallet.error.RuleViolationException;
import dev.payments.wallet.error.UnauthenticatedException;
import dev.payments.wallet.error.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 "problem details" response:
 *
 * <pre>{@code
 * HTTP/1.1 422 Unprocessable Content
 * Content-Type: application/problem+json
 *
 * {
 *   "title": "Unprocessable Content",
 *   "status": 422,
 *   "detail": "invalid input: amount",
 *   "code": "invalid_input",
 *   "fields": { "amount": "must be positive" },
 *   "request_id": "4f1c..."
 * }
 * }</pre>
 *
 * <p>{@code code} is the contract: a short, stable string clients can branch
 * on. {@code detail} is for humans and may change. This class is the only
 * place that knows how our exceptions become HTTP.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} means Spring MVC's own
 * errors (malformed JSON, unknown routes, wrong methods) use the same shape.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PaymentsException.class)
    ResponseEntity<Object> handlePaymentsException(PaymentsException ex) {
        // The compiler checks this switch covers every PaymentsException:
        // the hierarchy is sealed.
        ProblemDetail problem = switch (ex) {
            case NotFoundException e -> problem(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
            case ConflictException e -> problem(HttpStatus.CONFLICT, "conflict", e.getMessage());
            case ValidationException e -> {
                ProblemDetail p = problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_input", e.getMessage());
                p.setProperty("fields", e.fields());
                yield p;
            }
            case RuleViolationException e -> problem(HttpStatus.UNPROCESSABLE_CONTENT, e.code(), e.getMessage());
            case UnauthenticatedException e -> problem(HttpStatus.UNAUTHORIZED, "unauthenticated", e.getMessage());
            case IdempotencyException e -> problem(switch (e.reason()) {
                case KEY_REQUIRED -> HttpStatus.BAD_REQUEST;
                case KEY_REUSED -> HttpStatus.UNPROCESSABLE_CONTENT;
                case IN_PROGRESS -> HttpStatus.CONFLICT;
            }, e.reason().code(), e.getMessage());
        };
        return ResponseEntity.of(problem).build();
    }

    /**
     * Anything else is our bug. Log it with its stack trace, and tell the client
     * nothing about it: exception messages can leak SQL, hostnames or data.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("request failed", ex);
        return ResponseEntity.of(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "internal server error")).build();
    }

    /** Bean Validation failures ({@code @Valid} request bodies) use the same shape as ours. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return handlePaymentsException(new ValidationException(fields));
    }

    /**
     * Every response Spring MVC builds for its own exceptions passes through
     * here, with its body already built: we add our {@code code} and
     * {@code request_id} to it.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
            problem.setProperty("code", codeFor(status));
            problem.setProperty("request_id", RequestIdFilter.current());
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setProperty("request_id", RequestIdFilter.current());
        return problem;
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "malformed_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 406 -> "not_acceptable";
            case 415 -> "unsupported_media_type";
            default -> status.is5xxServerError() ? "internal" : "bad_request";
        };
    }
}
