package dev.payments.wallet.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.payments.wallet.IntegrationTest;
import dev.payments.wallet.ledger.Account;
import dev.payments.wallet.ledger.WalletService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One trace from an HTTP request, through the outbox table and the relay's
 * later thread, to the Kafka record. Without the traceparent column, the trace
 * would end at the database.
 */
@IntegrationTest
class TracePropagationIT {

    @Autowired
    RestTestClient client;

    @Autowired
    WalletService wallets;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaContainer kafka;

    @Test
    void theTraceOfADepositReachesItsKafkaRecord() {
        Account wallet = wallets.createWallet(UUID.randomUUID(), "USD");
        String traceId = HexFormat.of().formatHex(randomBytes(16));
        String incoming = "00-" + traceId + "-" + HexFormat.of().formatHex(randomBytes(8)) + "-01";

        client.post().uri("/v1/wallets/{id}/deposits", wallet.id())
                .header("X-User-ID", wallet.ownerId().toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("traceparent", incoming) // as the gateway would send it
                .body(Map.of("amount", 100, "currency", "USD"))
                .exchange()
                .expectStatus().isCreated();

        String stored = jdbc.sql("""
                        SELECT traceparent FROM outbox
                        WHERE message_key = :key AND payload->>'type' = 'wallet.deposit.completed.v1'
                        """)
                .param("key", wallet.id().toString()).query(String.class).single();
        assertThat(stored).as("the outbox row remembers the trace").contains(traceId);

        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "trace-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("wallet.events"));
            List<ConsumerRecord<String, String>> mine = new ArrayList<>();
            await().atMost(Duration.ofSeconds(15)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (r.key().equals(wallet.id().toString()) && r.value().contains("deposit.completed")) {
                        mine.add(r);
                    }
                });
                return !mine.isEmpty();
            });
            var header = mine.getFirst().headers().lastHeader("traceparent");
            assertThat(header).as("the relay publishes inside the same trace").isNotNull();
            assertThat(new String(header.value(), StandardCharsets.UTF_8)).contains(traceId);
        }
    }

    @Test
    void theOutboxBacklogIsAMetric() {
        client.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("outbox_unpublished{application=\"wallet-service\""));
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        ThreadLocalRandom.current().nextBytes(b);
        return b;
    }
}
