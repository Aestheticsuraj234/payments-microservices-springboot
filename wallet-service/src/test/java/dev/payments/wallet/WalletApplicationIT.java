package dev.payments.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import dev.payments.wallet.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Starts the whole application on a random port and talks to it over real
 * HTTP. Slower than a slice test, so we keep few of these: they prove the
 * pieces are wired together.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class WalletApplicationIT {

    @Autowired
    RestTestClient client;

    @Test
    void reportsHealthy() {
        client.get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void answersUnknownRoutesWithAProblem() {
        client.get().uri("/v1/nothing-here")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists(RequestIdFilter.HEADER)
                .expectBody()
                .jsonPath("$.code").isEqualTo("not_found")
                .jsonPath("$.request_id").value(id -> assertThat(id).isNotNull());
    }
}
