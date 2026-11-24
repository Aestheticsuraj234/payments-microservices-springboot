package dev.payments.wallet.events;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox events to Kafka, oldest first, and marks them published.
 *
 * <p>Publishing and marking are two writes to two systems again, so a crash
 * between them publishes an event twice: delivery is <em>at least once</em>.
 * That's the deal the outbox pattern makes. Consumers drop duplicates by the
 * event id (chapter 9); what can never happen is a lost event, or an event for
 * a change that rolled back.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** An outbox row waiting to be published. */
    record Message(long seq, UUID eventId, String topic, String key, String payload) {
    }

    /** Sends one message to the broker, and returns only once the broker has it. */
    interface Sender {
        void send(Message message) throws Exception;
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Sender sender;
    private final OutboxProperties properties;

    public OutboxRelay(JdbcClient jdbc, PlatformTransactionManager txManager, KafkaOperations<String, String> kafka,
            OutboxProperties properties) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(txManager);
        this.properties = properties;
        this.sender = message -> {
            var record = new ProducerRecord<>(message.topic(), message.key(), message.payload());
            record.headers().add("event_id", message.eventId().toString().getBytes(StandardCharsets.UTF_8));
            // Wait for the broker's acknowledgement: only then is it safe to
            // mark the row published.
            kafka.send(record).get(10, TimeUnit.SECONDS);
        };
    }

    @Scheduled(fixedDelayString = "${wallet.outbox.poll-interval}")
    void poll() {
        int published;
        do {
            published = publishBatch();
        } while (published == properties.batchSize()); // a full batch: there may be more
    }

    /** Publishes up to one batch of events and returns how many it published. */
    public int publishBatch() {
        Integer count = transactions.execute(status -> {
            // FOR UPDATE locks the rows until we commit. SKIP LOCKED passes
            // over rows another instance's relay has locked, so several
            // instances share the work and never publish the same rows at once.
            List<Message> batch = jdbc.sql("""
                            SELECT seq, event_id, topic, message_key, payload::text
                            FROM outbox
                            WHERE published_at IS NULL
                            ORDER BY seq
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                            """)
                    .param("limit", properties.batchSize())
                    .query((rs, n) -> new Message(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getString(4), rs.getString(5)))
                    .list();
            List<Long> sent = sendInOrder(batch, sender);
            if (!sent.isEmpty()) {
                // Mark what made it, even when a later event failed.
                jdbc.sql("UPDATE outbox SET published_at = now() WHERE seq = ANY(:seqs)")
                        .param("seqs", sent.toArray(Long[]::new))
                        .update();
            }
            return sent.size();
        });
        return count == null ? 0 : count;
    }

    /**
     * Sends messages in order, and stops at the first failure, so no event
     * overtakes one before it. Returns the seqs that were sent.
     */
    static List<Long> sendInOrder(List<Message> batch, Sender sender) {
        List<Long> sent = new ArrayList<>();
        for (Message m : batch) {
            try {
                sender.send(m);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("outbox: could not publish event {}, will retry: {}", m.eventId(), e.toString());
                break;
            }
            sent.add(m.seq());
        }
        return sent;
    }

    /** Deletes published events after the retention period: the outbox is a queue, not an archive. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    void deletePublished() {
        int deleted = jdbc.sql("""
                        DELETE FROM outbox
                        WHERE published_at < now() - make_interval(secs => :retention)
                        """)
                .param("retention", properties.retention().toSeconds())
                .update();
        if (deleted > 0) {
            log.info("outbox: deleted {} published events", deleted);
        }
    }
}
