package dev.payments.wallet.ledger;

import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a database transaction, and runs <em>the whole transaction</em>
 * again if PostgreSQL aborted it because it collided with another one: a
 * deadlock ({@code 40P01}) or a serialization failure ({@code 40001}). Spring
 * translates both into a {@link ConcurrencyFailureException}.
 *
 * <p>Both errors mean "nothing happened, try again", so retrying is safe, as
 * long as the work has no side effects outside the transaction. The retry
 * must wrap the transaction, never run inside it: once Postgres aborts a
 * transaction, every later statement in it fails too.
 *
 * <p>We use Spring Framework's own {@link RetryTemplate} (new in Spring 7)
 * with exponential backoff and jitter: transactions that just collided wait
 * different random times, so they don't all come back at once and collide again.
 */
@Component
public class RetryingTransactions {

    private final TransactionTemplate transactions;
    private final RetryTemplate retry;

    public RetryingTransactions(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        // Our row locks rely on READ COMMITTED: each statement sees all work
        // committed before it started, including the work we waited for. Ask
        // for it explicitly, so a different server default can't break that.
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.retry = new RetryTemplate(RetryPolicy.builder()
                .includes(ConcurrencyFailureException.class)
                .maxRetries(4)                       // 5 attempts in all
                .delay(Duration.ofMillis(10))
                .multiplier(2)
                .jitter(Duration.ofMillis(5))
                .maxDelay(Duration.ofMillis(200))
                .build());
    }

    /**
     * Runs {@code work} in a transaction, retrying the whole transaction on
     * collisions. Called inside a transaction that is already running, it just
     * joins it: only the outermost caller owns the transaction, so only it may
     * retry. Retrying inside would rerun statements in an aborted transaction.
     */
    public <T> T run(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return work.get();
        }
        return retry.invoke(() -> transactions.execute(status -> work.get()));
    }
}
