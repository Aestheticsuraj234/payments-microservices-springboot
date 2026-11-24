package dev.payments.wallet.events;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes events to the outbox table. The caller's transaction decides: the
 * event exists if, and only if, the change it describes commits.
 *
 * <p>Why not send to Kafka right here? Because then there are two systems and
 * no transaction spanning both. Send first and roll back: consumers hear about
 * money that never moved. Commit first and crash before sending: money moved
 * and nobody hears about it. Writing the event to the same database, in the
 * same transaction, removes the gap; the relay delivers it afterwards.
 */
@Component
public class Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String topic;
    private final TraceContexts traces;

    public Outbox(JdbcClient jdbc, JsonMapper json, OutboxProperties properties, TraceContexts traces) {
        this.jdbc = jdbc;
        this.json = json;
        this.topic = properties.topic();
        this.traces = traces;
    }

    /** Records an event about {@code walletId}. Must run inside the transaction that made it happen. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String type, UUID walletId, Object data) {
        var event = new WalletEvents.Envelope(UUID.randomUUID(), type, Instant.now(), walletId, data);
        jdbc.sql("""
                        INSERT INTO outbox (event_id, topic, message_key, payload, traceparent)
                        VALUES (:id, :topic, :key, CAST(:payload AS json), :traceparent)
                        """)
                .param("id", event.id())
                .param("topic", topic)
                .param("key", walletId.toString())
                .param("payload", json.writeValueAsString(event))
                // The trace of the request that made the change: the relay continues it.
                .param("traceparent", traces.current())
                .update();
    }
}
