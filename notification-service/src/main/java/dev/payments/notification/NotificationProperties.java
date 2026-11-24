package dev.payments.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param topic           where the wallet's events arrive
 * @param deadLetterTopic where messages go that we gave up on
 * @param from            the sender address of our emails
 */
@Validated
@ConfigurationProperties("notification")
public record NotificationProperties(
        @NotBlank String topic,
        @NotBlank String deadLetterTopic,
        @NotBlank String from,
        @NotNull @Valid Retry retry) {

    public record Retry(@Min(0) int attempts, @NotNull Duration initialDelay, @DecimalMin("1.0") double multiplier) {
    }
}
