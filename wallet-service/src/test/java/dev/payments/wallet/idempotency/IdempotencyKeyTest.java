package dev.payments.wallet.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.payments.wallet.error.IdempotencyException;
import dev.payments.wallet.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import tools.jackson.databind.json.JsonMapper;

class IdempotencyKeyTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private byte[] fingerprint(String method, String path, Object body) {
        return IdempotencyKey.of("k", "scope", method, path, body, json).fingerprint();
    }

    @Test
    void theSameRequestHasTheSameFingerprint() {
        assertThat(fingerprint("POST", "/v1/transfers", new Money(100, "USD")))
                .isEqualTo(fingerprint("POST", "/v1/transfers", new Money(100, "USD")));
    }

    @Test
    void anyDifferenceChangesIt() {
        byte[] original = fingerprint("POST", "/v1/wallets/a/deposits", new Money(100, "USD"));
        assertThat(fingerprint("POST", "/v1/wallets/a/deposits", new Money(101, "USD"))).isNotEqualTo(original);
        assertThat(fingerprint("POST", "/v1/wallets/a/deposits", new Money(100, "EUR"))).isNotEqualTo(original);
        assertThat(fingerprint("POST", "/v1/wallets/b/deposits", new Money(100, "USD"))).isNotEqualTo(original);
        assertThat(fingerprint("PUT", "/v1/wallets/a/deposits", new Money(100, "USD"))).isNotEqualTo(original);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void aKeyIsRequired(String header) {
        var ex = catchThrowableOfType(IdempotencyException.class,
                () -> IdempotencyKey.of(header, "scope", "POST", "/", new Money(1, "USD"), json));
        assertThat(ex.reason()).isEqualTo(IdempotencyException.Reason.KEY_REQUIRED);
    }

    @Test
    void aKeyIsAtMost255Characters() {
        IdempotencyKey.of("k".repeat(255), "scope", "POST", "/", new Money(1, "USD"), json);
        var ex = catchThrowableOfType(IdempotencyException.class,
                () -> IdempotencyKey.of("k".repeat(256), "scope", "POST", "/", new Money(1, "USD"), json));
        assertThat(ex.reason()).isEqualTo(IdempotencyException.Reason.KEY_REQUIRED);
    }
}
