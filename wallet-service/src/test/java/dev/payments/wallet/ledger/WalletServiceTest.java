package dev.payments.wallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.payments.wallet.config.WalletProperties;
import dev.payments.wallet.error.NotFoundException;
import dev.payments.wallet.events.Outbox;
import dev.payments.wallet.error.ValidationException;
import dev.payments.wallet.money.Money;
import dev.payments.wallet.money.MoneyRules;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The service's rules, with the repository mocked: these tests run in
 * milliseconds and need no database. What the SQL does is the integration
 * tests' job.
 */
class WalletServiceTest {

    private final WalletProperties props = new WalletProperties(1_000_000, new WalletProperties.PageSize(20, 100),
            new WalletProperties.Idempotency(java.time.Duration.ofHours(24), java.time.Duration.ofMinutes(1)));
    private final LedgerRepository ledger = mock(LedgerRepository.class);
    private final WalletService service = new WalletService(ledger, new MoneyRules(props), props,
            mock(Outbox.class));

    private final Account usdWallet = wallet("USD");

    private static Account wallet(String currency) {
        return new Account(UUID.randomUUID(), AccountKind.WALLET, UUID.randomUUID(),
                currency + " wallet", currency, Direction.CREDIT, Instant.now());
    }

    private Account known(Account account) {
        when(ledger.findAccount(account.id())).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void reportsEveryProblemWithANewWalletAtOnce() {
        var ex = catchThrowableOfType(ValidationException.class, () -> service.createWallet(null, "dollars"));
        assertThat(ex.fields()).containsOnlyKeys("owner_id", "currency");
    }

    @Test
    void refusesCurrenciesWeCannotSettle() {
        when(ledger.findSystemAccount("settlement", "XYZ")).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(ValidationException.class,
                () -> service.createWallet(UUID.randomUUID(), "XYZ"));
        assertThat(ex.fields()).containsEntry("currency", "unsupported currency");
    }

    @Test
    void hidesSystemAccounts() {
        var settlement = new Account(UUID.randomUUID(), AccountKind.SYSTEM, null,
                "settlement", "USD", Direction.DEBIT, Instant.now());
        when(ledger.findAccount(settlement.id())).thenReturn(Optional.of(settlement));
        catchThrowableOfType(NotFoundException.class, () -> service.wallet(UUID.randomUUID(), settlement.id()));
    }

    @Test
    void refusesADepositInTheWrongCurrency() {
        when(ledger.findAccount(usdWallet.id())).thenReturn(Optional.of(usdWallet));

        var ex = catchThrowableOfType(ValidationException.class,
                () -> service.deposit(usdWallet.ownerId(), usdWallet.id(), new Money(500, "JPY")));

        assertThat(ex.fields()).containsEntry("currency", "must match the wallet's currency, USD");
        verify(ledger, never()).post(anyString(), any());
    }

    @Test
    void validatesPageSizeAndCursor() {
        var ex = catchThrowableOfType(ValidationException.class,
                () -> service.entries(usdWallet.ownerId(), usdWallet.id(), "garbage!", 0));
        assertThat(ex.fields()).containsOnlyKeys("limit", "cursor");
    }

    @Test
    void otherPeoplesWalletsDoNotExist() {
        Account mine = known(wallet("USD"));
        catchThrowableOfType(NotFoundException.class, () -> service.wallet(UUID.randomUUID(), mine.id()));
        service.wallet(mine.ownerId(), mine.id());
    }

}
