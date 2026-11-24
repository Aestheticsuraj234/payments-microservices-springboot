package dev.payments.gateway;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param jwkSetUri      where the auth service publishes the keys that verify access tokens
 * @param issuer         the {@code iss} every access token must carry
 * @param audience       the {@code aud} every access token must include
 * @param allowedOrigins browser origins allowed to call the API (CORS)
 */
@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @NotBlank String jwkSetUri,
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull List<String> allowedOrigins) {
}
