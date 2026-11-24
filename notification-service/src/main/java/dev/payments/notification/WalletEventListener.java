package dev.payments.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the wallet's events. If this method returns, Spring Kafka commits the
 * offset: the message is done. If it throws, the error handler
 * ({@link KafkaConfig}) retries it with backoff, or parks it on the
 * dead-letter topic.
 */
@Component
class WalletEventListener {

    private static final Logger log = LoggerFactory.getLogger(WalletEventListener.class);

    private final JsonMapper json;
    private final Notifier notifier;

    WalletEventListener(JsonMapper json, Notifier notifier) {
        this.json = json;
        this.notifier = notifier;
    }

    @KafkaListener(topics = "${notification.topic}")
    void on(ConsumerRecord<String, String> record) {
        WalletEvent event;
        try {
            event = json.readValue(record.value(), WalletEvent.class);
        } catch (JacksonException e) {
            throw new PermanentException("not a wallet event", e);
        }
        for (Notification n : Notifications.about(event)) {
            if (notifier.deliver(n)) {
                log.info("event {}: emailed user {}: {}", event.id(), n.userId(), n.subject());
            } else {
                log.info("event {}: user {} already notified, skipping", event.id(), n.userId());
            }
        }
    }
}
