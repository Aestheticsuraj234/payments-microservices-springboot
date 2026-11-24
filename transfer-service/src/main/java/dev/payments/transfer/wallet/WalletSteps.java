package dev.payments.transfer.wallet;

import dev.payments.transfer.saga.Saga;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import dev.payments.transfer.saga.StepOutcome;
import dev.payments.transfer.saga.Transfer;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Runs a saga step against the wallet, and translates what happened into a
 * {@link StepOutcome}. The hold's id is the transfer's id, so every retry of
 * a step is the same idempotent call.
 *
 * <p>Every call goes through the "wallet" circuit breaker. While it's open,
 * calls fail at once without touching the network: a struggling wallet gets
 * room to recover instead of a queue of sagas hammering it.
 */
@Component
public class WalletSteps {

    private final WalletClient wallet;
    private final CircuitBreaker breaker;

    public WalletSteps(WalletClient wallet, CircuitBreakerRegistry breakers) {
        this.wallet = wallet;
        this.breaker = breakers.circuitBreaker("wallet");
    }

    public StepOutcome run(Saga.Step step, Transfer t) {
        try {
            breaker.executeRunnable(() -> {
                switch (step) {
                    case HOLD -> wallet.placeHold(t.id(),
                            new WalletClient.PlaceHold(t.fromWalletId(), t.userId(), t.amount(), t.currency()));
                    case CAPTURE -> wallet.capture(t.id(), new WalletClient.Capture(t.toWalletId()));
                    case RELEASE -> wallet.release(t.id());
                }
            });
            return new StepOutcome.Succeeded();
        } catch (CallNotPermittedException e) {
            // The breaker is open: we didn't even try. Certainly "I don't know".
            return new StepOutcome.Failed("wallet circuit breaker is open");
        } catch (RestClientResponseException e) {
            return isRejection(e.getStatusCode())
                    ? new StepOutcome.Rejected(code(e))
                    : new StepOutcome.Failed(e.getStatusCode() + " " + code(e));
        } catch (RestClientException e) {
            // Timeouts and connection errors: the step may or may not have run.
            return new StepOutcome.Failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * A 4xx is the wallet's considered answer: asking again gets the same one.
     * Except 408 and 429, which mean "not now". 5xx means "something broke":
     * maybe after the step ran, maybe before.
     */
    static boolean isRejection(HttpStatusCode status) {
        return status.is4xxClientError() && status.value() != 408 && status.value() != 429;
    }

    /** The {@code code} from a Problem Details body, or the status if there isn't one. */
    private static String code(RestClientResponseException e) {
        try {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            if (body != null && body.get("code") instanceof String code) {
                return code;
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return "http_" + e.getStatusCode().value();
    }
}
