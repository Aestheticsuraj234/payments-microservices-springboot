package dev.payments.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * @param issuer             the {@code iss} claim: who issued a token
 * @param audience           the {@code aud} claim: who a token is for
 * @param accessTokenTtl     how long an access token works. Short, because it
 *                           can't be revoked: a stolen one works until it expires.
 * @param refreshTokenTtl    how long a session lasts without a new login
 * @param privateKeyLocation the RSA key that signs access tokens; null makes a
 *                           throwaway key at startup
 */
@Validated
@ConfigurationProperties("auth")
public record AuthProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @Nullable Resource privateKeyLocation) {
}
