package dev.payments.wallet;

import org.springframework.boot.SpringApplication;

/**
 * Runs the wallet service against a PostgreSQL container, with nothing to set up:
 * {@code ./mvnw -pl wallet-service spring-boot:test-run}.
 */
public class TestWalletApplication {

    public static void main(String[] args) {
        SpringApplication.from(WalletApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
