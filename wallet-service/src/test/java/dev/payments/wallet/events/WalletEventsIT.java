package dev.payments.wallet.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.payments.wallet.IntegrationTest;
import dev.payments.wallet.holds.HoldService;
import dev.payments.wallet.ledger.Account;
import dev.payments.wallet.ledger.WalletService;
import dev.payments.wallet.money.Money;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Changes to wallets reach Kafka through the outbox, and only changes that committed. */
@IntegrationTest
class WalletEventsIT {

    @Autowired
    WalletService wallets;

    @Autowired
    HoldService holds;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    JsonMapper json;

    private KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of("wallet.events"));
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    /** Every event received so far about {@code walletId}, in the order they arrived. */
    private List<JsonNode> eventsFor(UUID walletId) {
        consumer.poll(Duration.ofMillis(200)).forEach(received::add);
        return received.stream()
                .filter(r -> r.key().equals(walletId.toString()))
                .map(r -> json.readTree(r.value()))
                .toList();
    }

    private long outboxRows(UUID walletId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE message_key = :key")
                .param("key", walletId.toString()).query(Long.class).single();
    }

    @Test
    void aDepositIsPublishedWithItsWalletAsTheKey() {
        Account wallet = wallets.createWallet(UUID.randomUUID(), "USD");
        wallets.deposit(wallet.ownerId(), wallet.id(), new Money(1050, "USD"));

        await().atMost(Duration.ofSeconds(15)).until(() -> eventsFor(wallet.id()).size() == 2);
        List<JsonNode> events = eventsFor(wallet.id());

        assertThat(events.get(0).get("type").asString()).isEqualTo(WalletEvents.WALLET_CREATED);
        JsonNode deposit = events.get(1);
        assertThat(deposit.get("type").asString()).isEqualTo(WalletEvents.DEPOSIT_COMPLETED);
        assertThat(deposit.get("aggregate_id").asString()).isEqualTo(wallet.id().toString());
        assertThat(deposit.at("/data/amount").asLong()).isEqualTo(1050);
        assertThat(deposit.at("/data/wallet/owner_id").asString()).isEqualTo(wallet.ownerId().toString());

        // The event id travels as a header too, so consumers can dedupe without parsing.
        var record = received.stream().filter(r -> r.key().equals(wallet.id().toString())).toList().get(1);
        assertThat(new String(record.headers().lastHeader("event_id").value(), StandardCharsets.UTF_8))
                .isEqualTo(deposit.get("id").asString());

        // And the relay marked both rows published.
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.sql(
                        "SELECT count(*) FROM outbox WHERE message_key = :key AND published_at IS NULL")
                .param("key", wallet.id().toString()).query(Long.class).single() == 0);
    }

    @Test
    void aWalletsEventsArriveInTheOrderTheyHappened() {
        Account wallet = wallets.createWallet(UUID.randomUUID(), "EUR");
        for (long amount = 1; amount <= 5; amount++) {
            wallets.deposit(wallet.ownerId(), wallet.id(), new Money(amount, "EUR"));
        }

        await().atMost(Duration.ofSeconds(15)).until(() -> eventsFor(wallet.id()).size() == 6);
        assertThat(eventsFor(wallet.id()).stream().skip(1).map(e -> e.at("/data/amount").asLong()))
                .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void aChangeThatRollsBackLeavesNoEvent() {
        Account wallet = wallets.createWallet(UUID.randomUUID(), "GBP");
        long before = outboxRows(wallet.id());

        // A deposit whose surrounding transaction rolls back afterwards.
        try {
            tx.executeWithoutResult(status -> {
                wallets.deposit(wallet.ownerId(), wallet.id(), new Money(100, "GBP"));
                throw new IllegalStateException("something after the deposit failed");
            });
        } catch (IllegalStateException expected) {
        }

        assertThat(outboxRows(wallet.id())).isEqualTo(before);
        assertThat(wallets.balance(wallet.ownerId(), wallet.id()).amount()).isZero();
    }

    @Test
    void aCapturedHoldIsOneTransferEventAboutTheSender() {
        Account from = wallets.createWallet(UUID.randomUUID(), "JPY");
        Account to = wallets.createWallet(UUID.randomUUID(), "JPY");
        wallets.deposit(from.ownerId(), from.id(), new Money(500, "JPY"));
        UUID holdId = UUID.randomUUID();
        holds.place(holdId, from.ownerId(), from.id(), new Money(200, "JPY"));
        holds.capture(holdId, to.id());

        await().atMost(Duration.ofSeconds(15)).until(() -> eventsFor(from.id()).stream()
                .anyMatch(e -> e.get("type").asString().equals(WalletEvents.TRANSFER_COMPLETED)));
        JsonNode transfer = eventsFor(from.id()).stream()
                .filter(e -> e.get("type").asString().equals(WalletEvents.TRANSFER_COMPLETED))
                .findFirst().orElseThrow();
        assertThat(transfer.at("/data/from/id").asString()).isEqualTo(from.id().toString());
        assertThat(transfer.at("/data/to/owner_id").asString()).isEqualTo(to.ownerId().toString());
        assertThat(transfer.at("/data/amount").asLong()).isEqualTo(200);
    }
}
