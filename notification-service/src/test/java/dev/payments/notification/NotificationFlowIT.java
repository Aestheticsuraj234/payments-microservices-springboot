package dev.payments.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The notification service with real PostgreSQL, Kafka and Mailpit (an SMTP
 * server that keeps every email and has an HTTP API to read them), and a fake
 * auth service. Events are published the way the wallet's relay publishes them.
 */
@SpringBootTest(properties = "notification.retry.initial-delay=100ms")
@Import(NotificationFlowIT.Containers.class)
class NotificationFlowIT {

    static final GenericContainer<?> mailpit = new GenericContainer<>("axllent/mailpit:v1.33")
            .withExposedPorts(1025, 8025);
    static final FakeAuth auth = new FakeAuth();

    static {
        mailpit.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.2.2");
        }

        /** In production the wallet creates this topic; here nobody else will. */
        @Bean
        NewTopic walletEvents() {
            return TopicBuilder.name("wallet.events").partitions(3).replicas(1).build();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", mailpit::getHost);
        registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
        registry.add("spring.http.serviceclient.auth.base-url", auth::url);
    }

    @AfterAll
    static void stop() {
        auth.stop();
        mailpit.stop();
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaContainer kafkaContainer;

    @Autowired
    JdbcClient jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    private static String wallet(UUID owner) {
        return "{\"id\": \"" + UUID.randomUUID() + "\", \"owner_id\": \"" + owner + "\", \"currency\": \"USD\"}";
    }

    private String deposit(UUID eventId, UUID owner, long amount) {
        return """
                {"id": "%s", "type": "wallet.deposit.completed.v1", "occurred_at": "2026-11-24T10:00:00Z",
                 "aggregate_id": "%s", "data": {"transaction_id": "%s", "wallet": %s, "amount": %d,
                 "some_field_added_later": true}}"""
                .formatted(eventId, UUID.randomUUID(), UUID.randomUUID(), wallet(owner), amount);
    }

    private void publish(String payload) throws Exception {
        kafka.send("wallet.events", UUID.randomUUID().toString(), payload).get();
    }

    /** Subjects of every email Mailpit holds for this address. */
    private List<String> inbox(String address) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025) + "/api/v1/messages?limit=500")).build();
        JsonNode messages = json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).get("messages");
        return messages.valueStream()
                .filter(m -> m.get("To").valueStream().anyMatch(to -> to.get("Address").asString().equals(address)))
                .map(m -> m.get("Subject").asString())
                .toList();
    }

    @Test
    void aDepositEmailsTheOwner() throws Exception {
        UUID owner = auth.user();
        publish(deposit(UUID.randomUUID(), owner, 1050));

        await().atMost(Duration.ofSeconds(20)).until(() -> !inbox(auth.email(owner)).isEmpty());
        assertThat(inbox(auth.email(owner))).containsExactly("Deposit received: 10.50 USD");
    }

    @Test
    void theSameEventTwiceSendsOneEmail() throws Exception {
        UUID owner = auth.user();
        UUID eventId = UUID.randomUUID();
        // The outbox delivers at least once: this is the duplicate it warned about.
        publish(deposit(eventId, owner, 200));
        publish(deposit(eventId, owner, 200));

        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                        "SELECT count(*) FROM notifications WHERE event_id = :id").param("id", eventId)
                .query(Long.class).single() == 1);
        // Give the duplicate time to be processed, then check it was skipped.
        Thread.sleep(1_000);
        assertThat(inbox(auth.email(owner))).hasSize(1);
    }

    @Test
    void aTransferEmailsBothSides() throws Exception {
        UUID sender = auth.user();
        UUID receiver = auth.user();
        publish("""
                {"id": "%s", "type": "wallet.transfer.completed.v1", "occurred_at": "2026-11-24T10:00:00Z",
                 "aggregate_id": "%s", "data": {"transaction_id": "%s", "from": %s, "to": %s, "amount": 300}}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), wallet(sender), wallet(receiver)));

        await().atMost(Duration.ofSeconds(20)).until(() ->
                !inbox(auth.email(sender)).isEmpty() && !inbox(auth.email(receiver)).isEmpty());
        assertThat(inbox(auth.email(receiver))).containsExactly("You received 3.00 USD");
        assertThat(inbox(auth.email(sender))).containsExactly("You sent 3.00 USD");
    }

    @Test
    void aShortOutageIsRetriedAway() throws Exception {
        UUID owner = auth.user();
        auth.failNext(owner, 503, 503);
        publish(deposit(UUID.randomUUID(), owner, 75));

        await().atMost(Duration.ofSeconds(20)).until(() -> !inbox(auth.email(owner)).isEmpty());
    }

    @Test
    void messagesThatCanNeverWorkGoToTheDeadLetterTopic() throws Exception {
        UUID marker = UUID.randomUUID();
        UUID nobody = UUID.randomUUID(); // auth answers 404 for this user
        publish(deposit(marker, nobody, 100));
        publish("this is not json " + marker);

        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-" + marker,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("wallet.events.dlt"));
            var parked = new java.util.ArrayList<String>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> parked.add(r.value()));
                return parked.stream().filter(v -> v.contains(marker.toString())).count() == 2;
            });
        }
    }
}
