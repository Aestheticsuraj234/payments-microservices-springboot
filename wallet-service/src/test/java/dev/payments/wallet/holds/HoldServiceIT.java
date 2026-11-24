package dev.payments.wallet.holds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.payments.wallet.IntegrationTest;
import dev.payments.wallet.error.ConflictException;
import dev.payments.wallet.error.NotFoundException;
import dev.payments.wallet.error.RuleViolationException;
import dev.payments.wallet.ledger.Account;
import dev.payments.wallet.ledger.WalletService;
import dev.payments.wallet.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Holds against real PostgreSQL: every step idempotent, no step able to overdraw or double-pay. */
@IntegrationTest
class HoldServiceIT {

    @Autowired
    HoldService holds;

    @Autowired
    WalletService wallets;

    private Account funded(String currency, long amount) {
        Account w = wallets.createWallet(UUID.randomUUID(), currency);
        if (amount > 0) {
            wallets.deposit(w.ownerId(), w.id(), new Money(amount, currency));
        }
        return w;
    }

    private long balance(Account w) {
        return wallets.balance(w.ownerId(), w.id()).amount();
    }

    @Test
    void placeThenCaptureMovesTheMoney() {
        Account alice = funded("USD", 1_000);
        Account bob = funded("USD", 0);
        UUID id = UUID.randomUUID();

        holds.place(id, alice.ownerId(), alice.id(), new Money(300, "USD"));
        assertThat(balance(alice)).as("held money can't be spent").isEqualTo(700);
        assertThat(balance(bob)).isZero();

        Hold captured = holds.capture(id, bob.id());
        assertThat(captured.status()).isEqualTo(Hold.Status.CAPTURED);
        assertThat(balance(alice)).isEqualTo(700);
        assertThat(balance(bob)).isEqualTo(300);
    }

    @Test
    void releaseGivesTheMoneyBack() {
        Account alice = funded("EUR", 500);
        UUID id = UUID.randomUUID();
        holds.place(id, alice.ownerId(), alice.id(), new Money(500, "EUR"));
        assertThat(balance(alice)).isZero();

        assertThat(holds.release(id).status()).isEqualTo(Hold.Status.RELEASED);
        assertThat(balance(alice)).isEqualTo(500);
    }

    @Test
    void everyStepIsIdempotent() {
        Account alice = funded("GBP", 1_000);
        Account bob = funded("GBP", 0);
        UUID id = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            holds.place(id, alice.ownerId(), alice.id(), new Money(400, "GBP"));
        }
        assertThat(balance(alice)).as("held once").isEqualTo(600);
        for (int i = 0; i < 3; i++) {
            holds.capture(id, bob.id());
        }
        assertThat(balance(bob)).as("paid once").isEqualTo(400);
    }

    @Test
    void theSameHoldIdWithDifferentTermsIsAConflict() {
        Account alice = funded("USD", 1_000);
        UUID id = UUID.randomUUID();
        holds.place(id, alice.ownerId(), alice.id(), new Money(100, "USD"));
        catchThrowableOfType(ConflictException.class,
                () -> holds.place(id, alice.ownerId(), alice.id(), new Money(999, "USD")));
    }

    @Test
    void aSettledHoldCannotBeSettledTheOtherWay() {
        Account alice = funded("USD", 1_000);
        Account bob = funded("USD", 0);
        UUID id = UUID.randomUUID();
        holds.place(id, alice.ownerId(), alice.id(), new Money(100, "USD"));
        holds.capture(id, bob.id());

        var ex = catchThrowableOfType(RuleViolationException.class, () -> holds.release(id));
        assertThat(ex.code()).isEqualTo(HoldService.HOLD_SETTLED);
        assertThat(balance(bob)).isEqualTo(100);
    }

    @Test
    void refusesWhatCannotWork() {
        Account alice = funded("USD", 50);
        Account yen = funded("JPY", 0);

        var poor = catchThrowableOfType(RuleViolationException.class,
                () -> holds.place(UUID.randomUUID(), alice.ownerId(), alice.id(), new Money(100, "USD")));
        assertThat(poor.code()).isEqualTo(HoldService.INSUFFICIENT_FUNDS);

        catchThrowableOfType(NotFoundException.class, () -> holds.place(UUID.randomUUID(), UUID.randomUUID(),
                alice.id(), new Money(10, "USD")));

        UUID id = UUID.randomUUID();
        holds.place(id, alice.ownerId(), alice.id(), new Money(10, "USD"));
        var mismatch = catchThrowableOfType(RuleViolationException.class, () -> holds.capture(id, yen.id()));
        assertThat(mismatch.code()).isEqualTo(HoldService.CURRENCY_MISMATCH);
    }

    @Test
    void concurrentHoldsNeverOverdraw() throws Exception {
        // Chapter 3's test, now for holds: 30 holds of 100 against 1,000.
        Account alice = funded("USD", 1_000);
        var start = new CountDownLatch(1);
        List<Future<Hold>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(30)) {
            for (int i = 0; i < 30; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return holds.place(UUID.randomUUID(), alice.ownerId(), alice.id(), new Money(100, "USD"));
                }));
            }
            start.countDown();
        }
        long placed = results.stream().filter(f -> f.state() == Future.State.SUCCESS).count();
        assertThat(placed).isEqualTo(10);
        assertThat(balance(alice)).isZero();
    }
}
