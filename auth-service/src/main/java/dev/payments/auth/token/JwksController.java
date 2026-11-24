package dev.payments.auth.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public half of the signing key as a JSON Web Key Set, the
 * standard place where verifiers (our gateway, chapter 6) fetch it.
 */
@RestController
class JwksController {

    private final Map<String, Object> jwks;

    JwksController(RSAKey signingKey) {
        // toPublicJWK() drops the private parts: this is safe to publish.
        this.jwks = new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return jwks;
    }
}
