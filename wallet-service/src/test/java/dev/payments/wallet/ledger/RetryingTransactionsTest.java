package dev.payments.wallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RetryingTransactionsTest {

    private final NoOpTransactionManager txManager = new NoOpTransactionManager();
    private final RetryingTransactions transactions = new RetryingTransactions(txManager);

    @Test
    void retriesTheWholeTransactionAfterADeadlock() {
        var attempts = new AtomicInteger();

        String result = transactions.run(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new CannotAcquireLockException("deadlock detected");
            }
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(attempts).hasValue(3);
        assertThat(txManager.begun).as("a fresh transaction per attempt").isEqualTo(3);
    }

    @Test
    void givesUpAfterFiveAttempts() {
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> transactions.run(() -> {
            attempts.incrementAndGet();
            throw new CannotAcquireLockException("deadlock detected");
        })).isInstanceOf(CannotAcquireLockException.class);
        assertThat(attempts).hasValue(5);
    }

    @Test
    void neverRetriesOtherErrors() {
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> transactions.run(() -> {
            attempts.incrementAndGet();
            throw new DataIntegrityViolationException("not balanced");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void insideAnotherTransactionItJoinsAndNeverRetries() {
        var attempts = new AtomicInteger();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> transactions.run(() -> {
                attempts.incrementAndGet();
                throw new CannotAcquireLockException("deadlock detected");
            })).isInstanceOf(CannotAcquireLockException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(attempts).as("the outer transaction's owner retries, not us").hasValue(1);
        assertThat(txManager.begun).isZero();
    }
}
