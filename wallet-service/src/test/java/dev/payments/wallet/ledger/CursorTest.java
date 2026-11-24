package dev.payments.wallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorTest {

    @Test
    void roundTrips() {
        assertThat(Cursor.decode(Cursor.encode(42))).hasValue(42);
    }

    @Test
    void noCursorMeansTheFirstPage() {
        assertThat(Cursor.decode(null)).hasValue(Long.MAX_VALUE);
        assertThat(Cursor.decode("")).hasValue(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"!!!", "bm90LWEtbnVtYmVy" /* not-a-number */, "MA" /* 0 */, "LTU" /* -5 */,
            "KzU" /* +5 */, "MDU" /* 05 */, "NDI="  /* 42, padded */})
    void rejectsAnythingWeDidNotHandOut(String cursor) {
        assertThat(Cursor.decode(cursor)).isEmpty();
    }
}
