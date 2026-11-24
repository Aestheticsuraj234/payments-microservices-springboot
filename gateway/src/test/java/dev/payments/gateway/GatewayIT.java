package dev.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * The gateway with real Redis (rate limits) in a container, in front of fake
 * downstream services that echo what they receive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(GatewayIT.Redis.class)
class GatewayIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class Redis {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        }
    }

    static final FakeServices services = new FakeServices();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.services.auth", services::url);
        registry.add("gateway.services.wallet", services::url);
        // A small budget, so the rate limit test doesn't need hundreds of requests.
        registry.add("gateway.rate-limit.replenish-rate", () -> 1);
        registry.add("gateway.rate-limit.burst-capacity", () -> 3);
    }

    @AfterAll
    static void stop() {
        services.stop();
    }

    @Autowired
    WebTestClient client;

    @Test
    void publicRoutesNeedNoToken() {
        client.post().uri("/v1/auth/login").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("/v1/auth/login")
                .jsonPath("$.user").isEmpty();
    }

    @Test
    void everythingElseNeedsAToken() {
        client.get().uri("/v1/wallets/anything").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody().jsonPath("$.code").isEqualTo("unauthenticated");
    }

    @Test
    void tellsServicesWhoTheVerifiedCallerIs() {
        UUID user = UUID.randomUUID();
        client.get().uri("/v1/wallets/123/balance")
                .header("Authorization", "Bearer " + services.token(user))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("/v1/wallets/123/balance")
                .jsonPath("$.user").isEqualTo(user.toString());
    }

    @Test
    void aClientCannotClaimToBeSomeoneElse() {
        UUID user = UUID.randomUUID();
        String victim = UUID.randomUUID().toString();

        client.get().uri("/v1/wallets/1")
                .header("Authorization", "Bearer " + services.token(user))
                .header("X-User-ID", victim)
                .exchange()
                .expectBody().jsonPath("$.user").isEqualTo(user.toString());

        client.post().uri("/v1/auth/login")
                .header("X-User-ID", victim)
                .exchange()
                .expectBody().jsonPath("$.user").isEmpty();
    }

    @Test
    void rejectsExpiredForeignAndMisaddressedTokens() throws Exception {
        UUID user = UUID.randomUUID();
        var otherKey = new RSAKeyGenerator(2048).keyID(services.key.getKeyID()).generate();
        List<String> bad = List.of(
                FakeServices.token(services.key, user, "payments", Instant.now().minus(Duration.ofHours(1))),
                FakeServices.token(otherKey, user, "payments", Instant.now()),
                FakeServices.token(services.key, user, "another-system", Instant.now()),
                "not-a-jwt");
        for (String token : bad) {
            client.get().uri("/v1/wallets/1")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @Test
    void limitsEachUsersRequestRate() {
        String token = services.token(UUID.randomUUID());
        List<HttpStatusCode> statuses = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            statuses.add(client.get().uri("/v1/wallets/1")
                    .header("Authorization", "Bearer " + token)
                    .exchange().returnResult(String.class).getStatus());
        }
        assertThat(statuses.subList(0, 3)).allMatch(s -> s.equals(HttpStatus.OK));
        assertThat(statuses).contains(HttpStatus.TOO_MANY_REQUESTS);

        // Another user has their own budget.
        client.get().uri("/v1/wallets/1")
                .header("Authorization", "Bearer " + services.token(UUID.randomUUID()))
                .exchange().expectStatus().isOk();
    }

    @Test
    void givesEveryRequestAnIdAndPassesItOn() {
        var result = client.post().uri("/v1/auth/login").exchange()
                .expectHeader().exists("X-Request-ID")
                .expectBody(String.class).returnResult();
        String id = result.getResponseHeaders().getFirst("X-Request-ID");
        assertThat(result.getResponseBody()).contains("\"request_id\": \"" + id + "\"");
    }

    @Test
    void answersBrowserPreflightsForAllowedOriginsOnly() {
        client.options().uri("/v1/wallets")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
        client.options().uri("/v1/wallets")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isForbidden();
    }
}
