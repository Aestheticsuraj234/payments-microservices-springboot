package dev.payments.wallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.payments.wallet.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The wallet API end to end: HTTP in, PostgreSQL underneath. Each request
 * carries {@code X-User-ID}, as the gateway would set it.
 */
@IntegrationTest
class WalletApiIT {

    private static final String USER = "X-User-ID";

    @Autowired
    RestTestClient client;

    @SuppressWarnings("unchecked")
    private Map<String, Object> createWallet(UUID owner, String currency) {
        return client.post().uri("/v1/wallets")
                .header(USER, owner.toString())
                .body(Map.of("currency", currency))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .returnResult(Map.class).getResponseBody();
    }

    private void deposit(Map<String, Object> wallet, long amount, String currency) {
        client.post().uri("/v1/wallets/{id}/deposits", wallet.get("id"))
                .header(USER, (String) wallet.get("owner_id"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("amount", amount, "currency", currency))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.transaction_id").exists()
                .jsonPath("$.amount.amount").isEqualTo(amount);
    }

    @Test
    void opensAWalletForTheCallerAndDepositsIntoIt() {
        UUID owner = UUID.randomUUID();
        var wallet = createWallet(owner, "USD");
        assertThat(wallet).containsEntry("currency", "USD").containsEntry("owner_id", owner.toString());

        deposit(wallet, 1050, "USD");
        deposit(wallet, 250, "USD");

        client.get().uri("/v1/wallets/{id}/balance", wallet.get("id"))
                .header(USER, owner.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.wallet_id").isEqualTo(wallet.get("id"))
                .jsonPath("$.balance.amount").isEqualTo(1300)
                .jsonPath("$.balance.currency").isEqualTo("USD");
    }

    @Test
    void theOwnerComesFromTheGatewayNotTheBody() {
        UUID caller = UUID.randomUUID();
        client.post().uri("/v1/wallets")
                .header(USER, caller.toString())
                .body(Map.of("currency", "EUR", "owner_id", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.owner_id").isEqualTo(caller.toString());
    }

    @Test
    void requestsWithoutACallerAreRefused() {
        client.post().uri("/v1/wallets")
                .body(Map.of("currency", "USD"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("unauthenticated");
    }

    @Test
    void otherPeoplesWalletsAreNotFound() {
        var wallet = createWallet(UUID.randomUUID(), "USD");
        for (String path : List.of("/v1/wallets/{id}", "/v1/wallets/{id}/balance", "/v1/wallets/{id}/entries")) {
            client.get().uri(path, wallet.get("id"))
                    .header(USER, UUID.randomUUID().toString())
                    .exchange()
                    .expectStatus().isNotFound();
        }
    }

    @Test
    void oneWalletPerOwnerAndCurrency() {
        UUID owner = UUID.randomUUID();
        createWallet(owner, "EUR");
        createWallet(owner, "GBP"); // another currency is fine

        client.post().uri("/v1/wallets")
                .header(USER, owner.toString())
                .body(Map.of("currency", "EUR"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo("conflict");
    }

    @Test
    void refusesUnsupportedCurrencies() {
        client.post().uri("/v1/wallets")
                .header(USER, UUID.randomUUID().toString())
                .body(Map.of("currency", "CHF"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody().jsonPath("$.fields.currency").isEqualTo("unsupported currency");
    }

    @Test
    void unknownAndMalformedIdsAreBoth404() {
        for (String id : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
            client.get().uri("/v1/wallets/{id}", id)
                    .header(USER, UUID.randomUUID().toString())
                    .exchange()
                    .expectStatus().isNotFound()
                    .expectBody().jsonPath("$.code").isEqualTo("not_found");
        }
    }

    @Test
    void refusesInvalidDeposits() {
        var wallet = createWallet(UUID.randomUUID(), "JPY");
        client.post().uri("/v1/wallets/{id}/deposits", wallet.get("id"))
                .header(USER, (String) wallet.get("owner_id"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("amount", -5, "currency", "USD"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody()
                .jsonPath("$.fields.amount").exists()
                .jsonPath("$.fields.currency").exists();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagesThroughEntriesNewestFirst() {
        var wallet = createWallet(UUID.randomUUID(), "GBP");
        for (long amount = 1; amount <= 5; amount++) {
            deposit(wallet, amount, "GBP");
        }

        List<Object> amounts = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String uri = "/v1/wallets/" + wallet.get("id") + "/entries?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            Map<String, Object> page = client.get().uri(uri)
                    .header(USER, (String) wallet.get("owner_id"))
                    .exchange()
                    .expectStatus().isOk()
                    .returnResult(Map.class).getResponseBody();
            for (var entry : (List<Map<String, Object>>) page.get("entries")) {
                assertThat(entry).containsEntry("direction", "credit");
                amounts.add(((Map<String, Object>) entry.get("amount")).get("amount"));
            }
            cursor = (String) page.get("next_cursor");
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(amounts).containsExactly(5, 4, 3, 2, 1);
    }
}
