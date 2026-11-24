package dev.payments.wallet.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

class FieldErrorsTest {

    @Test
    void throwsNothingWhenEveryFieldIsValid() {
        assertThatNoException().isThrownBy(() -> new FieldErrors().throwIfAny());
    }

    @Test
    void reportsEveryInvalidFieldAtOnce() {
        var errors = new FieldErrors()
                .add("currency", "must be a currency code")
                .add("amount", "must be positive");

        var ex = catchThrowableOfType(ValidationException.class, errors::throwIfAny);

        assertThat(ex.fields())
                .containsEntry("amount", "must be positive")
                .containsEntry("currency", "must be a currency code");
        // Sorted, so the message is the same whatever order the checks ran in.
        assertThat(ex.getMessage()).isEqualTo("invalid input: amount, currency");
    }

    @Test
    void keepsTheFirstProblemForAField() {
        var errors = new FieldErrors().add("amount", "first").add("amount", "second");
        var ex = catchThrowableOfType(ValidationException.class, errors::throwIfAny);
        assertThat(ex.fields()).containsEntry("amount", "first");
    }
}
