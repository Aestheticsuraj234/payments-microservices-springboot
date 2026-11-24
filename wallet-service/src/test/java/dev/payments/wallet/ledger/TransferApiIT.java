package dev.payments.wallet.ledger;

import dev.payments.wallet.IntegrationTest;
import dev.payments.wallet.money.Money;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class TransferApiIT {

    @Autowired
    RestTestClient client;

    @Autowired
    WalletService wallets;

    @Test
    void movesMoneyAndReportsInsufficientFunds() {
        Account alice = wallets.createWallet(UUID.randomUUID(), "EUR");
        Account bob = wallets.createWallet(UUID.randomUUID(), "EUR");
        wallets.deposit(alice.id(), new Money(500, "EUR"));

        client.post().uri("/v1/transfers")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("from", alice.id().toString(), "to", bob.id().toString(), "amount", 300, "currency", "EUR"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.transaction_id").exists()
                .jsonPath("$.from").isEqualTo(alice.id().toString())
                .jsonPath("$.amount.amount").isEqualTo(300);

        client.post().uri("/v1/transfers")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("from", alice.id().toString(), "to", bob.id().toString(), "amount", 300, "currency", "EUR"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody().jsonPath("$.code").isEqualTo("insufficient_funds");

        client.get().uri("/v1/wallets/{id}/balance", bob.id())
                .exchange()
                .expectBody().jsonPath("$.balance.amount").isEqualTo(300);
    }
}
