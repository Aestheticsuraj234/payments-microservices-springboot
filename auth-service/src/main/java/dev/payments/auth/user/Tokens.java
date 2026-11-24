package dev.payments.auth.user;

import java.time.Duration;

/**
 * What a login or a refresh returns.
 *
 * @param accessToken  a JWT, sent with every request
 * @param expiresIn    how long the access token lasts
 * @param refreshToken opaque, used once, to get the next tokens
 */
public record Tokens(String accessToken, Duration expiresIn, String refreshToken) {
}
