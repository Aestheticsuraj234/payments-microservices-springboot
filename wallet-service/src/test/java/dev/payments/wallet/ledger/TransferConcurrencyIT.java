package dev.payments.wallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.payments.wallet.IntegrationTest;
import dev.payments.wallet.error.RuleViolationException;
import dev.payments.wallet.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Many transfers at the same moment, against real PostgreSQL. These are the
 * tests that would fail without row locks (overdrafts) or without lock
 * ordering (deadlocks).
 */
@IntegrationTest
class TransferConcurrencyIT {

    @Autowired
    WalletService wallets;

    private Account funded(long amount) {
        Account wallet = wallets.createWallet(UUID.randomUUID(), "USD");
        if (amount > 0) {
            wallets.deposit(wallet.id(), new Money(amount, "USD"));
        }
        return wallet;
    }

    /** Runs every task at once (as near as threads allow), and waits for them all. */
    private static List<Future<?>> runTogether(List<Callable<?>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // inspected by the caller
                }
            }
            return futures;
        }
    }

    @Test
    void neverOverdrawsUnderConcurrentSpending() throws Exception {
        Account from = funded(1_000);
        Account to = funded(0);

        // 30 transfers of 100 from a wallet that holds 1,000.
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> wallets.transfer(from.id().toString(), to.id().toString(), new Money(100, "USD")));
        }
        List<Future<?>> results = runTogether(tasks);

        long succeeded = results.stream().filter(f -> f.state() == Future.State.SUCCESS).count();
        long refused = results.stream()
                .filter(f -> f.state() == Future.State.FAILED)
                .filter(f -> f.exceptionNow() instanceof RuleViolationException e
                        && e.code().equals(WalletService.INSUFFICIENT_FUNDS))
                .count();

        assertThat(succeeded).isEqualTo(10);
        assertThat(refused).isEqualTo(20);
        assertThat(wallets.balance(from.id()).amount()).isZero();
        assertThat(wallets.balance(to.id()).amount()).isEqualTo(1_000);
    }

    @Test
    void transfersInOppositeDirectionsNeitherDeadlockNorLoseMoney() throws Exception {
        Account a = funded(10_000);
        Account b = funded(10_000);

        // A pays B and B pays A, 25 times each, all at once. Without lock
        // ordering, "A locks a, B locks b, each waits for the other" deadlocks.
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            tasks.add(() -> wallets.transfer(a.id().toString(), b.id().toString(), new Money(7, "USD")));
            tasks.add(() -> wallets.transfer(b.id().toString(), a.id().toString(), new Money(7, "USD")));
        }
        List<Future<?>> results = runTogether(tasks);

        assertThat(results).allMatch(f -> f.state() == Future.State.SUCCESS);
        assertThat(wallets.balance(a.id()).amount()).isEqualTo(10_000);
        assertThat(wallets.balance(b.id()).amount()).isEqualTo(10_000);
    }
}
