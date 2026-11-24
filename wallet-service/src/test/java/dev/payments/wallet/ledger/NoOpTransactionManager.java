package dev.payments.wallet.ledger;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** A transaction manager for unit tests: it begins, commits and rolls back nothing. */
class NoOpTransactionManager implements PlatformTransactionManager {

    int begun;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        begun++;
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
    }

    @Override
    public void rollback(TransactionStatus status) {
    }
}
