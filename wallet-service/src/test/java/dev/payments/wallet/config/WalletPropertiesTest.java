package dev.payments.wallet.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * ApplicationContextRunner starts a tiny Spring context with just the pieces we
 * name: fast, and focused on one thing, here how properties bind and validate.
 */
class WalletPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(WalletProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void bindsTheProperties() {
        runner.withPropertyValues(
                        "wallet.max-amount=500",
                        "wallet.page-size.default-size=10",
                        "wallet.page-size.max=50")
                .run(context -> {
                    var props = context.getBean(WalletProperties.class);
                    assertThat(props.maxAmount()).isEqualTo(500);
                    assertThat(props.pageSize().defaultSize()).isEqualTo(10);
                    assertThat(props.pageSize().max()).isEqualTo(50);
                });
    }

    @Test
    void refusesToStartWithAnInvalidConfiguration() {
        runner.withPropertyValues(
                        "wallet.max-amount=-1",
                        "wallet.page-size.default-size=10",
                        "wallet.page-size.max=50")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure().rootCause().hasMessageContaining("maxAmount"));
    }
}
