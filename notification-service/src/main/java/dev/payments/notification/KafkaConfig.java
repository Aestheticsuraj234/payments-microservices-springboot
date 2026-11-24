package dev.payments.notification;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "auth", types = AuthClient.class)
class KafkaConfig {

    /**
     * What happens when handling a message throws.
     *
     * <ol>
     *   <li>Retry it in place, waiting longer each time (500 ms, 1 s, 2 s, 4 s):
     *       the auth service or the mail server may be back in a moment.</li>
     *   <li>Then give up on it, and publish it to the dead-letter topic, with
     *       headers saying why, so the partition can move on. One poisoned
     *       message must not stop every email behind it.</li>
     *   <li>A {@link PermanentException} skips the retries: it can't ever work.</li>
     * </ol>
     */
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<?, ?> kafka, NotificationProperties properties) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> new TopicPartition(properties.deadLetterTopic(), record.partition()));
        var backOff = new ExponentialBackOffWithMaxRetries(properties.retry().attempts());
        backOff.setInitialInterval(properties.retry().initialDelay().toMillis());
        backOff.setMultiplier(properties.retry().multiplier());
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(PermanentException.class);
        return handler;
    }

    /**
     * We own the dead-letter topic. It has as many partitions as the topic it
     * shadows, so a dead letter keeps its partition number.
     */
    @Bean
    NewTopic deadLetterTopic(NotificationProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic()).partitions(3).replicas(1).build();
    }
}
