package dev.payments.transfer.wallet;

import dev.payments.transfer.saga.Saga;
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
 */
@Component
public class WalletSteps {

    private final WalletClient wallet;

    public WalletSteps(WalletClient wallet) {
        this.wallet = wallet;
    }

    public StepOutcome run(Saga.Step step, Transfer t) {
        try {
            switch (step) {
                case HOLD -> wallet.placeHold(t.id(),
                        new WalletClient.PlaceHold(t.fromWalletId(), t.userId(), t.amount(), t.currency()));
                case CAPTURE -> wallet.capture(t.id(), new WalletClient.Capture(t.toWalletId()));
                case RELEASE -> wallet.release(t.id());
            }
            return new StepOutcome.Succeeded();
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
