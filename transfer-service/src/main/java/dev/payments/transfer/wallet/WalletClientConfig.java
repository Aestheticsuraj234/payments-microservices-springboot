package dev.payments.transfer.wallet;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Registers {@link WalletClient} as a bean in the HTTP service group
 * "wallet". Spring Boot configures the group's RestClient from
 * {@code spring.http.serviceclient.wallet.*}: base URL, timeouts.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "wallet", types = WalletClient.class)
class WalletClientConfig {
}
