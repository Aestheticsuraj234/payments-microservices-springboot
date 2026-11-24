package dev.payments.wallet.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class KafkaTopicConfig {

    /**
     * Creates the topic at startup if it doesn't exist. Partitions are the
     * unit of parallelism: consumers in one group split them between them.
     * Order is kept within a partition, and all events of one wallet share a
     * key, so they share a partition and stay in order.
     */
    @Bean
    NewTopic walletEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(properties.partitions()).replicas(1).build();
    }
}
