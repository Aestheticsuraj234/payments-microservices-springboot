package dev.payments.wallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.payments.wallet.IntegrationTest;
import dev.payments.wallet.money.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger's rules are enforced by PostgreSQL itself, so a bug in Java code
 * can't break them. These tests try to break them, and expect the database to
 * refuse.
 */
@IntegrationTest
class LedgerInvariantsIT {

    @Autowired
    LedgerRepository ledger;

    @Autowired
    WalletService wallets;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    private Account settlement() {
        return ledger.findSystemAccount("settlement", "USD").orElseThrow();
    }

    @Test
    void anUnbalancedTransactionIsRejectedAtCommit() {
        Account wallet = wallets.createWallet(UUID.randomUUID(), "USD");

        // Credit the wallet 100 but debit settlement only 99: money from nowhere.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> ledger.post("bogus", List.of(
                Posting.debit(settlement(), 99),
                Posting.credit(wallet, 100)))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("is not balanced");

        assertThat(ledger.balance(wallet.id())).isZero();
    }

    @Test
    void anEntryMustMatchItsAccountsCurrency() {
        Account wallet = wallets.createWallet(UUID.randomUUID(), "USD");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> ledger.post("wrong currency", List.of(
                new Posting(settlement().id(), Direction.DEBIT, 100, "EUR"),
                new Posting(wallet.id(), Direction.CREDIT, 100, "EUR")))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void postingOutsideATransactionIsABug() {
        assertThatThrownBy(() -> ledger.post("no tx", List.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theLedgerIsAppendOnly() {
        Account wallet = wallets.createWallet(UUID.randomUUID(), "USD");
        wallets.deposit(wallet.ownerId(), wallet.id(), new Money(100, "USD"));

        assertThatThrownBy(() -> jdbc.sql("UPDATE entries SET amount = 1000000 WHERE account_id = :id")
                .param("id", wallet.id()).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM entries WHERE account_id = :id")
                .param("id", wallet.id()).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void theBooksAlwaysBalance() {
        Account wallet = wallets.createWallet(UUID.randomUUID(), "EUR");
        wallets.deposit(wallet.ownerId(), wallet.id(), new Money(700, "EUR"));

        // Across the whole ledger, debits equal credits in every currency.
        List<Long> imbalances = jdbc.sql("""
                        SELECT sum(CASE direction WHEN 'debit' THEN amount ELSE -amount END)
                        FROM entries GROUP BY currency
                        """)
                .query(Long.class).list();
        assertThat(imbalances).isNotEmpty().allMatch(sum -> sum == 0);
    }
}
