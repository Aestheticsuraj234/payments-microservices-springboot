package dev.payments.wallet.config;

import jakarta.validation.Valid;
import java.time.Duration;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The wallet service's settings, bound from the {@code wallet.*} properties.
 *
 * <p>A record makes the settings immutable, and {@code @Validated} checks them
 * at startup: a service with a broken configuration refuses to start, instead
 * of failing on the first request that needs the setting.
 *
 * @param maxAmount the largest amount, in minor units, that one deposit or
 *                  transfer may move. It catches typos (an extra zero) and keeps
 *                  sums far from overflowing a {@code long}.
 * @param pageSize  how many items a page of results holds
 * @param idempotency how long idempotency keys live
 */
@Validated
@ConfigurationProperties("wallet")
public record WalletProperties(
        @Positive long maxAmount,
        @NotNull @Valid PageSize pageSize,
        @NotNull @Valid Idempotency idempotency) {

    /**
     * @param defaultSize the page size when the client asks for none
     * @param max         the largest page size a client may ask for
     */
    public record PageSize(@Min(1) int defaultSize, @Min(1) @Max(1000) int max) {
    }

    /**
     * @param ttl   how long we remember a key and its response. Most retries
     *              come within seconds; a day also covers a phone that was
     *              offline overnight.
     * @param lease how long a claim on a key lasts. A request still "in
     *              progress" after this died before it could finish, and a
     *              retry may take the key over.
     */
    public record Idempotency(@NotNull Duration ttl, @NotNull Duration lease) {
    }
}
