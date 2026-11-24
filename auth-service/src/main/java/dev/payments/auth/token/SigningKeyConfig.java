package dev.payments.auth.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import dev.payments.auth.AuthProperties;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The key pair behind access tokens. The private key signs, and never leaves
 * this service. The public key verifies, and anyone may have it: we publish it
 * at {@code /.well-known/jwks.json}, so the gateway can check tokens without
 * calling us on every request.
 *
 * <p>This is asymmetric signing (RS256). With a shared secret (HS256) every
 * service that checks tokens could also forge them.
 */
@Configuration(proxyBeanMethods = false)
class SigningKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyConfig.class);

    @Bean
    RSAKey signingKey(AuthProperties properties) throws IOException, JOSEException, NoSuchAlgorithmException,
            InvalidKeySpecException {
        RSAPrivateCrtKey privateKey;
        RSAPublicKey publicKey;
        if (properties.privateKeyLocation() != null) {
            try (InputStream in = properties.privateKeyLocation().getInputStream()) {
                privateKey = (RSAPrivateCrtKey) RsaKeyConverters.pkcs8().convert(in);
            }
            // An RSA private key in CRT form carries the public exponent: derive the public key.
            publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        } else {
            log.warn("auth.private-key-location is not set: signing with a throwaway key. "
                    + "Tokens stop working when this process stops.");
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            privateKey = (RSAPrivateCrtKey) pair.getPrivate();
            publicKey = (RSAPublicKey) pair.getPublic();
        }
        // The key id is the key's own thumbprint: it changes when the key
        // does, so verifiers can hold an old and a new key during a rotation.
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE)
                .keyIDFromThumbprint()
                .build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    /**
     * Verifies access tokens for this service's own protected endpoints, like
     * {@code /v1/auth/me}: the signature, the expiry, the issuer and the audience.
     */
    @Bean
    JwtDecoder jwtDecoder(RSAKey signingKey, AuthProperties properties) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(properties.audience()))));
        return decoder;
    }
}
