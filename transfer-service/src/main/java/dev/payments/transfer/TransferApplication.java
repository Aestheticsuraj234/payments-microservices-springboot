package dev.payments.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The transfer service: moves money between users as a saga over the wallet's
 * holds. It owns the transfer's state; the wallet owns the money.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TransferApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransferApplication.class, args);
    }
}
