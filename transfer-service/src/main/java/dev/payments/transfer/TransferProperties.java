package dev.payments.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param maxAmount the largest transfer, in minor units
 * @param saga      how the orchestrator runs
 */
@Validated
@ConfigurationProperties("transfer")
public record TransferProperties(@Positive long maxAmount, @NotNull @Valid Saga saga) {

    /**
     * @param pollInterval how often to look for due transfers
     * @param batchSize    transfers claimed at a time
     * @param lease        how long a claim lasts: longer than any step
     * @param backoff      the first retry's delay; doubles each attempt
     * @param maxBackoff   the longest delay between retries
     */
    public record Saga(@NotNull Duration pollInterval, @Min(1) int batchSize, @NotNull Duration lease,
            @NotNull Duration backoff, @NotNull Duration maxBackoff) {
    }
}
