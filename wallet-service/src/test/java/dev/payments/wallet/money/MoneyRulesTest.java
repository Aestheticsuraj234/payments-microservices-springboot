package dev.payments.wallet.money;

import static org.assertj.core.api.Assertions.assertThat;

import dev.payments.wallet.config.WalletProperties;
import dev.payments.wallet.error.FieldErrors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** A plain unit test: no Spring, no database, milliseconds to run. */
class MoneyRulesTest {

    private final MoneyRules rules = new MoneyRules(
            new WalletProperties(1_000, new WalletProperties.PageSize(20, 100),
            new WalletProperties.Idempotency(java.time.Duration.ofHours(24), java.time.Duration.ofMinutes(1))));

    @ParameterizedTest
    @ValueSource(longs = {1, 999, 1_000})
    void acceptsPositiveAmountsUpToTheMaximum(long amount) {
        var errors = new FieldErrors();
        rules.checkAmount(errors, "amount", amount);
        assertThat(errors.isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE, 1_001, Long.MAX_VALUE})
    void rejectsZeroNegativeAndTooLargeAmounts(long amount) {
        var errors = new FieldErrors();
        rules.checkAmount(errors, "amount", amount);
        assertThat(errors.has("amount")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "EUR", "JPY"})
    void acceptsCurrencyCodes(String currency) {
        var errors = new FieldErrors();
        rules.checkCurrency(errors, "currency", currency);
        assertThat(errors.isEmpty()).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"usd", "US", "USDT", "U$D", " USD"})
    void rejectsAnythingElse(String currency) {
        var errors = new FieldErrors();
        rules.checkCurrency(errors, "currency", currency);
        assertThat(errors.has("currency")).isTrue();
    }
}
