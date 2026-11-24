package dev.payments.transfer.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.payments.transfer.FakeWallet;
import dev.payments.transfer.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/** The circuit breaker between the transfer service and the wallet, against a wallet that goes down. */
@IntegrationTest
class WalletBreakerIT {

    static final FakeWallet wallet = new FakeWallet();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.http.serviceclient.wallet.base-url", wallet::url);
        registry.add("resilience4j.circuitbreaker.instances.wallet.wait-duration-in-open-state", () -> "1s");
    }

    @AfterAll
    static void stop() {
        wallet.stop();
    }

    @Autowired
    RestTestClient client;

    @Autowired
    CircuitBreakerRegistry breakers;

    @AfterEach
    void reset() {
        wallet.lookupsDown = false;
        breakers.circuitBreaker("wallet").reset();
    }

    private HttpStatus send(FakeWallet.Wallet from, UUID to) {
        return (HttpStatus) client.post().uri("/v1/transfers")
                .header("X-User-ID", from.owner().toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("from", from.id(), "to", to, "amount", 10, "currency", "USD"))
                .exchange().returnResult(String.class).getStatus();
    }

    @Test
    void opensAfterRepeatedFailuresThenFailsFastThenRecovers() {
        var alice = wallet.wallet("USD");
        var bob = wallet.wallet("USD");
        CircuitBreaker breaker = breakers.circuitBreaker("wallet");
        wallet.lookupsDown = true;

        // Five failed calls (each request looks up a wallet) open the breaker.
        while (breaker.getState() == CircuitBreaker.State.CLOSED) {
            assertThat(send(alice, bob.id())).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Open: still 503, but the wallet isn't even asked.
        int before = wallet.lookups.get();
        long started = System.nanoTime();
        assertThat(send(alice, bob.id())).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
        assertThat(wallet.lookups.get()).isEqualTo(before);

        // The wallet recovers. After the wait, trial calls close the breaker again.
        wallet.lookupsDown = false;
        await().atMost(Duration.ofSeconds(10)).until(() -> send(alice, bob.id()) == HttpStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(5)).until(() -> breaker.getState() == CircuitBreaker.State.CLOSED);
    }

    @Test
    void refusalsDoNotCountAsFailures() {
        var alice = wallet.wallet("USD");
        // Twenty transfers to wallets that don't exist: 404s from a healthy wallet.
        for (int i = 0; i < 20; i++) {
            send(alice, UUID.randomUUID());
        }
        assertThat(breakers.circuitBreaker("wallet").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void anOpenBreakerShowsInHealthButDoesNotMakeUsUnhealthy() {
        breakers.circuitBreaker("wallet").transitionToOpenState();
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.circuitBreakers.details.wallet.details.state").isEqualTo("OPEN");
    }
}
