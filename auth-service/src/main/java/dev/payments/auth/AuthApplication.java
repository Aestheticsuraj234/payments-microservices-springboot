package dev.payments.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The auth service: users, passwords, and the tokens that prove who a request
 * comes from. It is the only service that ever sees a password or holds the
 * key that signs access tokens.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
