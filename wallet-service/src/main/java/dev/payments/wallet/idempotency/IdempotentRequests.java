package dev.payments.wallet.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What controllers call to make a request idempotent. It builds the key from
 * the request, runs the work through the {@link IdempotencyStore}, and turns the
 * stored response into an HTTP response.
 *
 * <pre>{@code
 * return idempotent.run(request, body, HttpStatus.CREATED, () -> service.deposit(...));
 * }</pre>
 */
@Component
public class IdempotentRequests {

    /**
     * Every caller shares one scope until chapter 6, where the gateway tells
     * us who the caller is and keys become unique per user.
     */
    static final String ANONYMOUS = "anonymous";

    private final IdempotencyStore store;
    private final JsonMapper json;

    public IdempotentRequests(IdempotencyStore store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    /**
     * Runs {@code work} once per {@code Idempotency-Key}. {@code body} is the
     * decoded request body, for the fingerprint. The result of {@code work}
     * becomes the JSON body of a {@code status} response, stored so retries get
     * exactly the same bytes, with an {@code Idempotent-Replayed: true} header.
     */
    public ResponseEntity<byte[]> run(HttpServletRequest request, Object body, HttpStatus status,
            Supplier<Object> work) {
        IdempotencyKey key = IdempotencyKey.of(request.getHeader(IdempotencyKey.HEADER), ANONYMOUS,
                request.getMethod(), request.getRequestURI(), body, json);
        StoredResponse response = store.run(key,
                () -> new StoredResponse(status.value(), json.writeValueAsBytes(work.get()), false));

        var builder = ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON);
        if (response.replayed()) {
            builder.header(IdempotencyKey.REPLAYED_HEADER, "true");
        }
        return builder.body(response.body());
    }
}
