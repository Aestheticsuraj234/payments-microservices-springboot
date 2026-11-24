package dev.payments.transfer.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;

class WalletStepsTest {

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422})
    void aConsideredNoIsARejection(int status) {
        assertThat(WalletSteps.isRejection(HttpStatusCode.valueOf(status))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void anythingElseMightWorkLater(int status) {
        assertThat(WalletSteps.isRejection(HttpStatusCode.valueOf(status))).isFalse();
    }
}
