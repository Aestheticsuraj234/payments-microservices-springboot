package dev.payments.wallet.events;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Saves the current trace as a string, and starts a span that continues it
 * later. Tracing follows a request across threads and services by itself, but
 * not across "write it to a table, pick it up later": for that, we carry the
 * W3C {@code traceparent} ourselves.
 */
@Component
public class TraceContexts {

    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceContexts(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The current trace as a traceparent header value, or null outside any trace. */
    public @Nullable String current() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /**
     * Starts a span that continues the trace in {@code traceparent}, or a new
     * trace if it's null. A null {@code kind} is an internal span.
     */
    public Span continueTrace(@Nullable String traceparent, String name, Span.@Nullable Kind kind) {
        Span.Builder builder = traceparent == null
                ? tracer.spanBuilder()
                : propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get);
        builder.name(name);
        if (kind != null) {
            builder.kind(kind);
        }
        return builder.start();
    }

    public Tracer tracer() {
        return tracer;
    }
}
