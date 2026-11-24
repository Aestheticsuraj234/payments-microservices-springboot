package dev.payments.auth;

import org.springframework.boot.SpringApplication;

/**
 * Runs the auth service against a PostgreSQL container, with nothing to set up:
 * {@code ./mvnw -pl auth-service spring-boot:test-run}.
 */
public class TestAuthApplication {

    public static void main(String[] args) {
        SpringApplication.from(AuthApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
