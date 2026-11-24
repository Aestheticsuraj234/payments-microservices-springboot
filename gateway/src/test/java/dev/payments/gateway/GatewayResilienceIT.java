package dev.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The gateway in front of a wallet service that isn't running. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(GatewayIT.Redis.class)
class GatewayResilienceIT {

    static final FakeServices services = new FakeServices();

    /** A port nothing listens on: connections are refused at once. */
    static String deadService() {
        try (var socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.services.auth", services::url);
        registry.add("gateway.services.wallet", GatewayResilienceIT::deadService);
        registry.add("gateway.services.transfer", services::url);
    }

    @AfterAll
    static void stop() {
        services.stop();
    }

    @Autowired
    WebTestClient client;

    @Autowired
    CircuitBreakerRegistry breakers;

    @Test
    void anUnreachableServiceGetsAQuick503InOurErrorShape() {
        String token = services.token(UUID.randomUUID());
        for (int i = 0; i < 12; i++) {
            client.get().uri("/v1/wallets/1")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().valueEquals("Retry-After", "10")
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("unavailable")
                    .jsonPath("$.request_id").exists();
        }
        // Ten failed calls in the window: the breaker is open, and requests
        // no longer even try to connect.
        assertThat(breakers.circuitBreaker("wallet").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        long started = System.nanoTime();
        client.get().uri("/v1/wallets/1").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isEqualTo(503);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
    }

    @Test
    void otherServicesAreUnaffected() {
        // The wallet's breaker is the wallet's: transfers still flow.
        client.post().uri("/v1/transfers")
                .header("Authorization", "Bearer " + services.token(UUID.randomUUID()))
                .exchange()
                .expectStatus().isOk();
        assertThat(breakers.circuitBreaker("transfer").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
