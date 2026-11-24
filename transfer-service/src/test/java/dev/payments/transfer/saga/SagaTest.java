package dev.payments.transfer.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The saga is data, so it can be checked without a database or a wallet. */
class SagaTest {

    @Test
    void theHappyPathHoldsThenCaptures() {
        assertThat(Saga.moveFrom(State.PENDING)).isEqualTo(new Saga.Move(Saga.Step.HOLD, State.FUNDS_HELD, State.FAILED));
        assertThat(Saga.moveFrom(State.FUNDS_HELD))
                .isEqualTo(new Saga.Move(Saga.Step.CAPTURE, State.COMPLETED, State.COMPENSATING));
    }

    @Test
    void compensationReleasesAndNeverGivesUp() {
        assertThat(Saga.moveFrom(State.COMPENSATING)).isEqualTo(new Saga.Move(Saga.Step.RELEASE, State.REVERSED, null));
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"PENDING", "FUNDS_HELD", "COMPENSATING"})
    void everyMoveIsAnAllowedTransition(State from) {
        Saga.Move move = Saga.moveFrom(from);
        assertThat(from.canBecome(move.next())).isTrue();
        if (move.rejected() != null) {
            assertThat(from.canBecome(move.rejected())).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"COMPLETED", "FAILED", "REVERSED"})
    void finalStatesHaveNoMoves(State state) {
        assertThat(state.isFinal()).isTrue();
        assertThatThrownBy(() -> Saga.moveFrom(state)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void moneyThatLeftTheWalletCanNeverJustFail() {
        // Once funds are held, the only ways out are completion or reversal:
        // "failed" would strand the held money.
        assertThat(State.FUNDS_HELD.canBecome(State.FAILED)).isFalse();
        assertThat(State.COMPENSATING.canBecome(State.FAILED)).isFalse();
        assertThat(State.PENDING.canBecome(State.COMPLETED)).isFalse();
    }
}
