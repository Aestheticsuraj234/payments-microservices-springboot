package dev.payments.transfer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The whole application on a random port, backed by PostgreSQL in a container.
 *
 * <p>Every integration test uses this same annotation, so they share one Spring
 * context and one container: Spring caches the context between test classes
 * whose configuration is identical.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@AutoConfigureTracing // tests see real spans and trace propagation, like production
@AutoConfigureMetrics // and real metrics at /actuator/prometheus
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
