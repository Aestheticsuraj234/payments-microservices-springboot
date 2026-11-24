package dev.payments.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import dev.payments.auth.AuthProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.BadJwtException;

/** Tokens signed and verified with real keys, no Spring context. */
class AccessTokensTest {

    private final AuthProperties props = new AuthProperties("payments-auth", "payments",
            Duration.ofMinutes(15), Duration.ofDays(30), null);
    private final SigningKeyConfig config = new SigningKeyConfig();

    private final RSAKey key = key();
    private final AccessTokens tokens = new AccessTokens(config.jwtEncoder(key), key, props);
    private final JwtDecoder decoder = decoder(key);

    private RSAKey key() {
        try {
            return config.signingKey(props);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JwtDecoder decoder(RSAKey k) {
        try {
            return config.jwtDecoder(k, props);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void carriesWhoTheUserIsAndWhoTheTokenIsFor() {
        UUID user = UUID.randomUUID();
        var jwt = decoder.decode(tokens.issue(user, Instant.now()));

        assertThat(jwt.getSubject()).isEqualTo(user.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("payments-auth");
        assertThat(jwt.getAudience()).containsExactly("payments");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getHeaders()).containsEntry("kid", key.getKeyID()).containsEntry("alg", "RS256");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void anExpiredTokenIsRejected() {
        String old = tokens.issue(UUID.randomUUID(), Instant.now().minus(Duration.ofHours(1)));
        assertThatThrownBy(() -> decoder.decode(old))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        JwtDecoder otherServicesDecoder = decoder(key());
        String token = tokens.issue(UUID.randomUUID(), Instant.now());
        assertThatThrownBy(() -> otherServicesDecoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void aTokenForAnotherAudienceIsRejected() {
        var elsewhere = new AuthProperties("payments-auth", "some-other-system",
                Duration.ofMinutes(15), Duration.ofDays(30), null);
        String token = new AccessTokens(config.jwtEncoder(key), key, elsewhere).issue(UUID.randomUUID(), Instant.now());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void thePublishedKeySetHasNoPrivateParts() {
        @SuppressWarnings("unchecked")
        var keys = (List<Map<String, Object>>) new JwksController(key).jwks().get("keys");
        assertThat(keys).singleElement().satisfies(k -> assertThat(k)
                .containsKeys("kty", "kid", "n", "e")
                .doesNotContainKeys("d", "p", "q", "dp", "dq", "qi"));
    }
}
