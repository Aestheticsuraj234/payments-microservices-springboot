package dev.payments.transfer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL for tests, in a throwaway container. {@code @ServiceConnection}
 * points Spring's datasource at it: no URLs or passwords to configure.
 *
 * <p>Why not H2? Our ledger's rules live in Postgres: a constraint trigger,
 * enums, a view. An in-memory imitation would let broken code pass.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }
}
