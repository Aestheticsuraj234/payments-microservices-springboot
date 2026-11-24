package dev.payments.wallet.holds;

import dev.payments.wallet.IntegrationTest;
import dev.payments.wallet.ledger.Account;
import dev.payments.wallet.ledger.WalletService;
import dev.payments.wallet.money.Money;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The internal API's JSON contract, which the transfer service depends on.
 * If this test has to change, the transfer service's WalletClient must too.
 */
@IntegrationTest
class InternalApiIT {

    @Autowired
    RestTestClient client;

    @Autowired
    WalletService wallets;

    @Test
    void holdCaptureAndReleaseOverHttp() {
        Account alice = wallets.createWallet(UUID.randomUUID(), "USD");
        Account bob = wallets.createWallet(UUID.randomUUID(), "USD");
        wallets.deposit(alice.ownerId(), alice.id(), new Money(500, "USD"));

        client.get().uri("/internal/wallets/{id}", bob.id()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.owner_id").isEqualTo(bob.ownerId().toString())
                .jsonPath("$.currency").isEqualTo("USD");

        UUID hold = UUID.randomUUID();
        client.put().uri("/internal/holds/{id}", hold)
                .body(Map.of("wallet_id", alice.id(), "owner_id", alice.ownerId(), "amount", 200, "currency", "USD"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("held")
                .jsonPath("$.amount.amount").isEqualTo(200);

        client.post().uri("/internal/holds/{id}/capture", hold)
                .body(Map.of("to_wallet_id", bob.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("captured")
                .jsonPath("$.settled_to").isEqualTo(bob.id().toString());

        client.post().uri("/internal/holds/{id}/release", hold).exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody().jsonPath("$.code").isEqualTo("hold_settled");
    }

    @Test
    void rejectionsCarryTheirCode() {
        Account poor = wallets.createWallet(UUID.randomUUID(), "EUR");
        client.put().uri("/internal/holds/{id}", UUID.randomUUID())
                .body(Map.of("wallet_id", poor.id(), "owner_id", poor.ownerId(), "amount", 1, "currency", "EUR"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody().jsonPath("$.code").isEqualTo("insufficient_funds");

        client.get().uri("/internal/wallets/{id}", UUID.randomUUID()).exchange()
                .expectStatus().isNotFound();
    }
}
