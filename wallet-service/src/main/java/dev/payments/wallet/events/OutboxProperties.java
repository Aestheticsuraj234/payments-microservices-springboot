package dev.payments.wallet.events;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param topic        the Kafka topic for every wallet event
 * @param partitions   how many partitions the topic gets when we create it
 * @param pollInterval how often the relay looks for unpublished events
 * @param batchSize    events per relay transaction
 * @param retention    how long published rows stay in the outbox table
 */
@Validated
@ConfigurationProperties("wallet.outbox")
public record OutboxProperties(
        @NotBlank String topic,
        @Min(1) int partitions,
        @NotNull Duration pollInterval,
        @Min(1) int batchSize,
        @NotNull Duration retention) {
}
