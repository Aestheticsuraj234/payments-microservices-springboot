package dev.payments.auth.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import dev.payments.auth.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class AuthApiIT {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    RestTestClient client;

    private String newEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private void register(String email) {
        client.post().uri("/v1/auth/register")
                .body(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.email").isEqualTo(email)
                .jsonPath("$.password_hash").doesNotExist();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> login(String email) {
        return client.post().uri("/v1/auth/login")
                .body(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .returnResult(Map.class).getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private RestTestClient.ResponseSpec refresh(Object token) {
        return client.post().uri("/v1/auth/refresh").body(Map.of("refresh_token", token)).exchange();
    }

    @Test
    void registersLogsInAndKnowsWhoYouAre() {
        String email = newEmail();
        register(email);

        var tokens = login(email);
        assertThat(tokens).containsEntry("token_type", "Bearer").containsEntry("expires_in", 900)
                .containsKeys("access_token", "refresh_token");

        client.get().uri("/v1/auth/me")
                .header("Authorization", "Bearer " + tokens.get("access_token"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.email").isEqualTo(email);
    }

    @Test
    void emailsAreUniqueInAnyLetterCase() {
        String email = newEmail();
        register(email);
        client.post().uri("/v1/auth/register")
                .body(Map.of("email", email.toUpperCase(), "password", PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
        login(email.toUpperCase()); // and logins don't care either
    }

    @Test
    void rejectsBadEmailsAndShortPasswords() {
        client.post().uri("/v1/auth/register")
                .body(Map.of("email", "not-an-email", "password", "short"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody()
                .jsonPath("$.fields.email").exists()
                .jsonPath("$.fields.password").exists();
    }

    @Test
    void aWrongPasswordAndAnUnknownEmailLookTheSame() {
        String email = newEmail();
        register(email);

        byte[] wrongPassword = client.post().uri("/v1/auth/login")
                .body(Map.of("email", email, "password", "not the password at all"))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("unauthenticated")
                .returnResult().getResponseBody();
        byte[] unknownEmail = client.post().uri("/v1/auth/login")
                .body(Map.of("email", newEmail(), "password", PASSWORD))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().returnResult().getResponseBody();

        String strip = "\"request_id\":\"[^\"]*\"";
        assertThat(new String(wrongPassword).replaceAll(strip, "")).isEqualTo(new String(unknownEmail).replaceAll(strip, ""));
    }

    @Test
    void protectedEndpointsNeedAValidToken() {
        client.get().uri("/v1/auth/me").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody().jsonPath("$.code").isEqualTo("unauthenticated");
        client.get().uri("/v1/auth/me").header("Authorization", "Bearer not.a.jwt").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void refreshRotatesAndReuseRevokesTheWholeFamily() {
        String email = newEmail();
        register(email);
        var first = login(email);

        @SuppressWarnings("unchecked")
        Map<String, Object> second = refresh(first.get("refresh_token")).expectStatus().isOk()
                .returnResult(Map.class).getResponseBody();
        assertThat(second.get("refresh_token")).isNotEqualTo(first.get("refresh_token"));

        // The first token comes back: someone kept a copy. Refuse it...
        refresh(first.get("refresh_token")).expectStatus().isUnauthorized();
        // ...and revoke its family, so the newer token dies too.
        refresh(second.get("refresh_token")).expectStatus().isUnauthorized();
        // A fresh login starts a new family and works.
        refresh(login(email).get("refresh_token")).expectStatus().isOk();
    }

    @Test
    void logoutEndsTheSession() {
        String email = newEmail();
        register(email);
        var tokens = login(email);

        client.post().uri("/v1/auth/logout").body(Map.of("refresh_token", tokens.get("refresh_token")))
                .exchange().expectStatus().isNoContent();
        refresh(tokens.get("refresh_token")).expectStatus().isUnauthorized();
    }

    @Test
    void anyoneCanVerifyTokensWithThePublishedKeys() throws Exception {
        String email = newEmail();
        register(email);
        String accessToken = (String) login(email).get("access_token");

        String jwks = new String(client.get().uri("/.well-known/jwks.json").exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody());
        SignedJWT jwt = SignedJWT.parse(accessToken);
        RSAKey key = JWKSet.parse(jwks).getKeyByKeyId(jwt.getHeader().getKeyID()).toRSAKey();

        assertThat(jwt.verify(new RSASSAVerifier(key))).isTrue();
    }
}
