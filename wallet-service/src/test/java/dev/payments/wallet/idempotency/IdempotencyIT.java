package dev.payments.wallet.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.payments.wallet.IntegrationTest;
import dev.payments.wallet.ledger.Account;
import dev.payments.wallet.ledger.WalletService;
import dev.payments.wallet.money.Money;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

/** Retrying a request that moves money must never move it twice. */
@IntegrationTest
class IdempotencyIT {

    @Autowired
    RestTestClient client;

    @Autowired
    WalletService wallets;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    private Account wallet() {
        return wallets.createWallet(UUID.randomUUID(), "USD");
    }

    private EntityExchangeResult<byte[]> deposit(Account wallet, String key, long amount) {
        return client.post().uri("/v1/wallets/{id}/deposits", wallet.id())
                .header(IdempotencyKey.HEADER, key)
                .body(Map.of("amount", amount, "currency", "USD"))
                .exchange()
                .expectBody().returnResult();
    }

    private long balance(Account wallet) {
        return wallets.balance(wallet.id()).amount();
    }

    @Test
    void aRetryGetsTheSameResponseAndMovesNoMoney() {
        Account wallet = wallet();
        String key = UUID.randomUUID().toString();

        var first = deposit(wallet, key, 500);
        var retry = deposit(wallet, key, 500);

        assertThat(first.getStatus()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getResponseHeaders().getFirst(IdempotencyKey.REPLAYED_HEADER)).isNull();
        assertThat(retry.getStatus()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getResponseHeaders().getFirst(IdempotencyKey.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(retry.getResponseBody()).as("byte for byte").isEqualTo(first.getResponseBody());
        assertThat(balance(wallet)).isEqualTo(500);
    }

    @Test
    void aKeyCannotBeReusedForADifferentRequest() {
        Account wallet = wallet();
        String key = UUID.randomUUID().toString();
        deposit(wallet, key, 500);

        var reused = deposit(wallet, key, 9_999);

        assertThat(reused.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(new String(reused.getResponseBody(), StandardCharsets.UTF_8)).contains("idempotency_key_reused");
        assertThat(balance(wallet)).isEqualTo(500);
    }

    @Test
    void requestsThatMoveMoneyNeedAKey() {
        client.post().uri("/v1/wallets/{id}/deposits", wallet().id())
                .body(Map.of("amount", 500, "currency", "USD"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("idempotency_key_required");
    }

    @Test
    void aFailedRequestReleasesItsKey() {
        Account wallet = wallet();
        String key = UUID.randomUUID().toString();

        assertThat(deposit(wallet, key, -1).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // The client fixes the amount and sends it again, with the same key.
        assertThat(deposit(wallet, key, 100).getStatus()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(wallet)).isEqualTo(100);
    }

    @Test
    void anAbandonedClaimIsTakenOver() {
        Account wallet = wallet();
        String key = UUID.randomUUID().toString();
        String path = "/v1/wallets/" + wallet.id() + "/deposits";
        var claim = IdempotencyKey.of(key, IdempotentRequests.ANONYMOUS, "POST", path, new Money(100, "USD"), json);
        // A request claimed this key ten minutes ago, then its process died.
        jdbc.sql("""
                        INSERT INTO idempotency_keys (scope, key, fingerprint, locked_at, expires_at)
                        VALUES (:scope, :key, :fingerprint, now() - interval '10 minutes', now() + interval '1 day')
                        """)
                .param("scope", claim.scope()).param("key", key).param("fingerprint", claim.fingerprint())
                .update();

        assertThat(deposit(wallet, key, 100).getStatus()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(wallet)).isEqualTo(100);
    }

    @Test
    void aFreshClaimMakesRetriesWait() {
        Account wallet = wallet();
        String key = UUID.randomUUID().toString();
        String path = "/v1/wallets/" + wallet.id() + "/deposits";
        var claim = IdempotencyKey.of(key, IdempotentRequests.ANONYMOUS, "POST", path, new Money(100, "USD"), json);
        // Another request claimed it a moment ago and is still running.
        jdbc.sql("""
                        INSERT INTO idempotency_keys (scope, key, fingerprint, expires_at)
                        VALUES (:scope, :key, :fingerprint, now() + interval '1 day')
                        """)
                .param("scope", claim.scope()).param("key", key).param("fingerprint", claim.fingerprint())
                .update();

        var retry = deposit(wallet, key, 100);
        assertThat(retry.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(new String(retry.getResponseBody(), StandardCharsets.UTF_8)).contains("request_in_progress");
        assertThat(balance(wallet)).isZero();
    }

    @Test
    void concurrentRetriesMoveMoneyOnce() throws Exception {
        Account wallet = wallet();
        String key = UUID.randomUUID().toString();
        int clients = 10;
        var start = new CountDownLatch(1);

        List<Future<HttpStatus>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return (HttpStatus) deposit(wallet, key, 250).getStatus();
                }));
            }
            start.countDown();
        }

        // Each one either ran it, replayed it, or was told to retry later.
        for (Future<HttpStatus> r : results) {
            assertThat(r.get()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
        }
        assertThat(balance(wallet)).as("credited exactly once").isEqualTo(250);
    }
}
