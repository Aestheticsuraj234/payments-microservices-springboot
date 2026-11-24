package dev.payments.transfer.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.payments.transfer.saga.Saga;
import dev.payments.transfer.saga.State;
import dev.payments.transfer.saga.StepOutcome;
import dev.payments.transfer.saga.Transfer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WalletStepsBreakerTest {

    @Test
    void anOpenBreakerFailsTheStepWithoutCallingTheWallet() {
        WalletClient wallet = mock(WalletClient.class);
        var breakers = CircuitBreakerRegistry.ofDefaults();
        breakers.circuitBreaker("wallet").transitionToForcedOpenState();
        var steps = new WalletSteps(wallet, breakers);
        var transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), UUID.randomUUID(),
                100, "USD", State.PENDING, "", 0, 1, Instant.now(), Instant.now(), null);

        StepOutcome outcome = steps.run(Saga.Step.HOLD, transfer);

        // "Failed", not "Rejected": the saga retries later, it doesn't give up.
        assertThat(outcome).isInstanceOf(StepOutcome.Failed.class);
        verifyNoInteractions(wallet);
    }
}
