package dev.payments.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The notification service: it reads the wallet's events and emails the
 * people they concern. Nobody calls it: it only listens. The wallet doesn't
 * know it exists.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
