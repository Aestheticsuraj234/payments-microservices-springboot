package dev.payments.transfer.saga;

import dev.payments.transfer.TransferProperties;
import dev.payments.transfer.wallet.WalletSteps;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives transfers through the saga, one step at a time. It is the saga's
 * <em>orchestrator</em>: one component that knows the whole flow and tells
 * the wallet what to do, as opposed to <em>choreography</em>, where each
 * service reacts to the others' events and nobody holds the whole picture.
 *
 * <p>Any number of instances may run: claims (leases) and versions keep them
 * out of each other's way, and if one dies, its leases run out and another
 * one carries on from the state in the database.
 */
@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final TransferRepository transfers;
    private final WalletSteps wallet;
    private final TransferProperties.Saga config;
    private final TraceContexts traces;
    private final MeterRegistry meters;

    public SagaOrchestrator(TransferRepository transfers, WalletSteps wallet, TransferProperties properties,
            TraceContexts traces, MeterRegistry meters) {
        this.transfers = transfers;
        this.wallet = wallet;
        this.config = properties.saga();
        this.traces = traces;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${transfer.saga.poll-interval}")
    void poll() {
        int claimed;
        do {
            claimed = runOnce();
        } while (claimed == config.batchSize()); // a full batch: more may be due
    }

    /** Claims a batch of due transfers and runs one step of each. Returns how many. */
    public int runOnce() {
        var batch = transfers.claim(config.batchSize(), config.lease());
        batch.forEach(this::step);
        return batch.size();
    }

    void step(Transfer t) {
        Saga.Move move = Saga.moveFrom(t.state());
        String step = move.step().name().toLowerCase();
        // Each step is a span in the trace of the request that created the
        // transfer, so the whole saga shows up under that one request.
        Span span = traces.continueTrace(t.traceparent(), "saga " + step, null)
                .tag("transfer.id", t.id().toString());
        try (var scope = traces.tracer().withSpan(span)) {
            runStep(t, move, step);
        } finally {
            span.end();
        }
    }

    private void runStep(Transfer t, Saga.Move move, String step) {
        StepOutcome outcome = wallet.run(move.step(), t);

        boolean recorded = switch (outcome) {
            case StepOutcome.Succeeded s -> {
                log.info("transfer {}: {} succeeded, now {}", t.id(), step, move.next());
                yield finished(move.next(), transfers.advance(t, move.next(), step, "succeeded", ""));
            }
            case StepOutcome.Rejected r when move.rejected() != null -> {
                log.warn("transfer {}: {} rejected ({}), now {}", t.id(), step, r.reason(), move.rejected());
                yield finished(move.rejected(), transfers.advance(t, move.rejected(), step, "rejected", r.reason()));
            }
            case StepOutcome.Rejected r -> {
                // A release must not be refused. If it is, a human must look:
                // keep retrying (slowly), and shout in the logs.
                log.error("transfer {}: {} rejected ({}), will keep retrying", t.id(), step, r.reason());
                yield transfers.retryLater(t, step, "rejected: " + r.reason(), nextAttempt(t.attempts()));
            }
            case StepOutcome.Failed f -> {
                // We don't know if it happened. The retry is the same idempotent call.
                Instant at = nextAttempt(t.attempts());
                log.warn("transfer {}: {} failed (attempt {}), retrying at {}: {}", t.id(), step,
                        t.attempts() + 1, at, f.detail());
                meters.counter("saga.step.retries", "step", step).increment();
                yield transfers.retryLater(t, step, f.detail(), at);
            }
        };
        if (!recorded) {
            log.info("transfer {} moved on without us", t.id());
        }
    }

    /**
     * Counts transfers that reached a final state, by state: the system's
     * business pulse. A rise in "reversed" or "failed" is worth a look.
     */
    private boolean finished(State next, boolean recorded) {
        if (recorded && next.isFinal()) {
            meters.counter("transfers.finished", "state", next.dbValue()).increment();
        }
        return recorded;
    }

    /** Exponential backoff with jitter: the delay doubles each attempt, capped, then randomised. */
    Instant nextAttempt(int attempts) {
        long base = config.backoff().toMillis() << Math.min(attempts, 16);
        long capped = Math.min(base, config.maxBackoff().toMillis());
        long jittered = capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        return Instant.now().plus(Duration.ofMillis(jittered));
    }
}
