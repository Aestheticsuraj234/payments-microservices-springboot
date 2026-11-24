package dev.payments.auth.token;

import com.nimbusds.jose.jwk.RSAKey;
import dev.payments.auth.AuthProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues access tokens: short-lived JWTs that say who a user is.
 *
 * <pre>
 * header  {"alg": "RS256", "kid": "&lt;key thumbprint&gt;"}
 * claims  {"iss": "payments-auth", "sub": "&lt;user id&gt;", "aud": ["payments"],
 *          "iat": ..., "exp": ..., "jti": "&lt;unique id&gt;"}
 * </pre>
 *
 * <p>Any service with the public key can check a token offline, so an access
 * token can't be revoked: that's why it lives only minutes. Refresh tokens,
 * which can be revoked, keep the session going.
 */
@Component
public class AccessTokens {

    private final JwtEncoder encoder;
    private final String keyId;
    private final AuthProperties properties;

    public AccessTokens(JwtEncoder encoder, RSAKey signingKey, AuthProperties properties) {
        this.encoder = encoder;
        this.keyId = signingKey.getKeyID();
        this.properties = properties;
    }

    public Duration ttl() {
        return properties.accessTokenTtl();
    }

    /** An access token for the user, issued at {@code now}: tests can pass a time in the past. */
    public String issue(UUID userId, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .audience(List.of(properties.audience()))
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .id(UUID.randomUUID().toString()) // unique per token, for logs and audits
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
