package dev.payments.transfer.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.payments.transfer.FakeWallet;
import dev.payments.transfer.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The saga end to end: the real transfer service and PostgreSQL, with a
 * scripted fake wallet that can refuse, fail, or time out on cue.
 */
@IntegrationTest
class TransferSagaIT {

    static final FakeWallet wallet = new FakeWallet();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.http.serviceclient.wallet.base-url", wallet::url);
        // Fast polling and short backoffs, so retries happen within the test.
        registry.add("transfer.saga.poll-interval", () -> "50ms");
        registry.add("transfer.saga.backoff", () -> "50ms");
        registry.add("transfer.saga.max-backoff", () -> "200ms");
        // These tests script many wallet failures on purpose. They test the saga, not the
        // circuit breaker (WalletBreakerIT does that), so keep the breaker from opening.
        registry.add("resilience4j.circuitbreaker.instances.wallet.minimum-number-of-calls", () -> 1000);
        registry.add("resilience4j.circuitbreaker.instances.wallet.sliding-window-size", () -> 1000);
    }

    @AfterAll
    static void stop() {
        wallet.stop();
    }

    @Autowired
    RestTestClient client;

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(FakeWallet.Wallet from, FakeWallet.Wallet to, long amount) {
        return client.post().uri("/v1/transfers")
                .header("X-User-ID", from.owner().toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("from", from.id(), "to", to.id(), "amount", amount, "currency", from.currency()))
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().exists("Location")
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(FakeWallet.Wallet owner, Object id) {
        return client.get().uri("/v1/transfers/{id}", id)
                .header("X-User-ID", owner.owner().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    private Map<String, Object> awaitFinal(FakeWallet.Wallet owner, Object id, String state) {
        await().atMost(Duration.ofSeconds(15)).until(() -> get(owner, id).get("state").equals(state));
        return get(owner, id);
    }

    @SuppressWarnings("unchecked")
    private static List<String> steps(Map<String, Object> transfer) {
        return ((List<Map<String, Object>>) transfer.get("steps")).stream()
                .map(s -> s.get("step") + ":" + s.get("outcome")).toList();
    }

    @Test
    void holdsThenCapturesThenCompletes() {
        var alice = wallet.wallet("USD");
        var bob = wallet.wallet("USD");

        var accepted = send(alice, bob, 300);
        assertThat(accepted).containsEntry("state", "pending");

        var done = awaitFinal(alice, accepted.get("id"), "completed");
        assertThat(steps(done)).containsExactly("hold:succeeded", "capture:succeeded");
        UUID id = UUID.fromString((String) accepted.get("id"));
        // The hold's id is the transfer's id: that's what makes retries safe.
        assertThat(wallet.callsFor(id)).containsExactly("hold " + id, "capture " + id + " -> " + bob.id());
    }

    @Test
    void aRefusedHoldFailsWithoutMovingAnything() {
        var alice = wallet.wallet("EUR");
        var bob = wallet.wallet("EUR");
        wallet.script("hold", alice.id(), "422:insufficient_funds");

        var accepted = send(alice, bob, 300);
        var done = awaitFinal(alice, accepted.get("id"), "failed");

        assertThat(done).containsEntry("failure_reason", "insufficient_funds");
        assertThat(steps(done)).containsExactly("hold:rejected");
        assertThat(wallet.callsFor(UUID.fromString((String) accepted.get("id")))).hasSize(1);
    }

    @Test
    void aRefusedCaptureIsCompensatedByAReleaseUntilItWorks() {
        var alice = wallet.wallet("GBP");
        var bob = wallet.wallet("GBP");
        wallet.script("capture", bob.id(), "422:currency_mismatch");
        // The release fails twice before it works: compensation must not give up.
        wallet.script("release", alice.id(), "503", "503");

        var accepted = send(alice, bob, 300);
        var done = awaitFinal(alice, accepted.get("id"), "reversed");

        assertThat(done).containsEntry("failure_reason", "currency_mismatch");
        assertThat(steps(done)).containsExactly(
                "hold:succeeded", "capture:rejected", "release:failed", "release:failed", "release:succeeded");
    }

    @Test
    void stepsThatFailAreRetriedUntilTheyGetAnAnswer() {
        var alice = wallet.wallet("USD");
        var bob = wallet.wallet("USD");
        wallet.script("hold", alice.id(), "503", "500");
        wallet.script("capture", bob.id(), "502");

        var accepted = send(alice, bob, 100);
        var done = awaitFinal(alice, accepted.get("id"), "completed");

        assertThat(steps(done)).containsExactly(
                "hold:failed", "hold:failed", "hold:succeeded", "capture:failed", "capture:succeeded");
    }

    @Test
    void theSameKeyGivesTheSameTransfer() {
        var alice = wallet.wallet("USD");
        var bob = wallet.wallet("USD");
        Map<String, Object> body = Map.of("from", alice.id(), "to", bob.id(), "amount", 50, "currency", "USD");

        String first = client.post().uri("/v1/transfers").header("X-User-ID", alice.owner().toString())
                .header("Idempotency-Key", "same").body(body).exchange()
                .expectStatus().isAccepted()
                .expectBody(Map.class).returnResult().getResponseBody().get("id").toString();
        client.post().uri("/v1/transfers").header("X-User-ID", alice.owner().toString())
                .header("Idempotency-Key", "same").body(body).exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals("Idempotent-Replayed", "true")
                .expectBody().jsonPath("$.id").isEqualTo(first);
        client.post().uri("/v1/transfers").header("X-User-ID", alice.owner().toString())
                .header("Idempotency-Key", "same")
                .body(Map.of("from", alice.id(), "to", bob.id(), "amount", 51, "currency", "USD")).exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody().jsonPath("$.code").isEqualTo("idempotency_key_reused");
    }

    @Test
    void checksTheWalletsBeforeAccepting() {
        var alice = wallet.wallet("USD");
        var bob = wallet.wallet("USD");
        var yen = wallet.wallet("JPY");

        // Bob's wallet as the source, sent by Alice: not hers.
        client.post().uri("/v1/transfers").header("X-User-ID", alice.owner().toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("from", bob.id(), "to", alice.id(), "amount", 50, "currency", "USD")).exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody().jsonPath("$.fields.from").isEqualTo("no such wallet");

        client.post().uri("/v1/transfers").header("X-User-ID", alice.owner().toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("from", alice.id(), "to", yen.id(), "amount", 50, "currency", "USD")).exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody().jsonPath("$.fields.to").exists();

        client.post().uri("/v1/transfers").header("X-User-ID", alice.owner().toString())
                .body(Map.of("from", alice.id(), "to", bob.id(), "amount", 50, "currency", "USD")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("idempotency_key_required");
    }

    @Test
    void onlyTheSenderCanSeeATransfer() {
        var alice = wallet.wallet("USD");
        var bob = wallet.wallet("USD");
        var accepted = send(alice, bob, 10);

        client.get().uri("/v1/transfers/{id}", accepted.get("id"))
                .header("X-User-ID", bob.owner().toString())
                .exchange()
                .expectStatus().isNotFound();
    }
}
