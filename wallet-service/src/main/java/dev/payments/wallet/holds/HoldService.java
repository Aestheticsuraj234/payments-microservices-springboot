package dev.payments.wallet.holds;

import dev.payments.wallet.error.ConflictException;
import dev.payments.wallet.error.FieldErrors;
import dev.payments.wallet.error.NotFoundException;
import dev.payments.wallet.error.RuleViolationException;
import dev.payments.wallet.events.Outbox;
import dev.payments.wallet.events.WalletEvents;
import dev.payments.wallet.ledger.Account;
import dev.payments.wallet.ledger.LedgerRepository;
import dev.payments.wallet.ledger.Posting;
import dev.payments.wallet.ledger.RetryingTransactions;
import dev.payments.wallet.ledger.Transaction;
import dev.payments.wallet.money.Money;
import dev.payments.wallet.money.MoneyRules;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Holds: the wallet's building blocks for the transfer saga.
 *
 * <pre>
 *   place:   wallet ──debit──▶ holds account      (the sender can't spend it any more)
 *   capture: holds account ──▶ receiver's wallet  (the transfer completes)
 *   release: holds account ──▶ sender's wallet    (the compensation: as if nothing happened)
 * </pre>
 *
 * <p>Every operation is idempotent, keyed by the hold id the caller chooses.
 * The saga retries steps after timeouts, and a timeout doesn't tell it whether
 * the step happened: so running a step twice must be the same as running it once.
 */
@Service
public class HoldService {

    public static final String INSUFFICIENT_FUNDS = "insufficient_funds";
    public static final String HOLD_SETTLED = "hold_settled";
    public static final String CURRENCY_MISMATCH = "currency_mismatch";

    /** The system account where held money waits, one per currency. */
    static final String HOLDS_ACCOUNT = "holds";

    private final LedgerRepository ledger;
    private final HoldRepository holds;
    private final MoneyRules moneyRules;
    private final RetryingTransactions transactions;
    private final Outbox outbox;

    public HoldService(LedgerRepository ledger, HoldRepository holds, MoneyRules moneyRules,
            RetryingTransactions transactions, Outbox outbox) {
        this.ledger = ledger;
        this.holds = holds;
        this.moneyRules = moneyRules;
        this.transactions = transactions;
        this.outbox = outbox;
    }

    /**
     * Sets money aside from the owner's wallet, as hold {@code id}. Calling it
     * again with the same terms returns the same hold and moves nothing.
     */
    public Hold place(UUID id, UUID ownerId, UUID walletId, Money amount) {
        var errors = new FieldErrors();
        moneyRules.checkAmount(errors, "amount", amount.amount());
        Account wallet = ledger.findAccount(walletId)
                .filter(Account::isWallet)
                .filter(a -> a.ownerId().equals(ownerId))
                .orElseThrow(() -> new NotFoundException("wallet not found"));
        if (!wallet.currency().equals(amount.currency())) {
            errors.add("currency", "must match the wallet's currency, " + wallet.currency());
        }
        errors.throwIfAny();

        return transactions.run(() -> {
            // The same rule as chapter 3: lock the wallet, then check its balance.
            ledger.lock(wallet.id());
            var existing = holds.find(id);
            if (existing.isPresent()) {
                Hold h = existing.get();
                if (!h.walletId().equals(wallet.id()) || h.amount() != amount.amount()) {
                    throw new ConflictException("hold " + id + " exists with different terms");
                }
                return h; // placed before: nothing to do
            }
            if (ledger.balance(wallet.id()) < amount.amount()) {
                throw new RuleViolationException(INSUFFICIENT_FUNDS, "insufficient funds");
            }
            Transaction tx = ledger.post("hold", List.of(
                    Posting.debit(wallet, amount.amount()),
                    Posting.credit(holdsAccount(wallet.currency()), amount.amount())));
            return holds.create(id, wallet.id(), amount.amount(), wallet.currency(), tx.id());
        });
    }

    /** Pays hold {@code id} into anyone's wallet: the transfer completes. */
    public Hold capture(UUID id, UUID toWalletId) {
        return settle(id, toWalletId);
    }

    /** Gives hold {@code id} back to the wallet it came from: the saga's compensation. */
    public Hold release(UUID id) {
        return settle(id, null);
    }

    private Hold settle(UUID id, @Nullable UUID toWalletId) {
        Hold.Status target = toWalletId == null ? Hold.Status.RELEASED : Hold.Status.CAPTURED;
        return transactions.run(() -> {
            Hold hold = holds.lock(id).orElseThrow(() -> new NotFoundException("hold not found"));
            if (hold.status() == target && (toWalletId == null || toWalletId.equals(hold.settledTo()))) {
                return hold; // settled before, the same way
            }
            if (hold.status() != Hold.Status.HELD) {
                throw new RuleViolationException(HOLD_SETTLED, "hold was already " + hold.status().dbValue());
            }
            Account source = ledger.findAccount(hold.walletId()).orElseThrow();
            Account destination = source; // a release always goes home
            if (toWalletId != null) {
                destination = ledger.findAccount(toWalletId)
                        .filter(Account::isWallet)
                        .orElseThrow(() -> new NotFoundException("wallet not found"));
                if (!destination.currency().equals(hold.currency())) {
                    throw new RuleViolationException(CURRENCY_MISMATCH, "wallets have different currencies");
                }
            }
            Transaction tx = ledger.post("hold " + target.dbValue(), List.of(
                    Posting.debit(holdsAccount(hold.currency()), hold.amount()),
                    Posting.credit(destination, hold.amount())));
            Hold settled = holds.settle(id, target, destination.id(), tx.id());
            if (target == Hold.Status.CAPTURED) {
                // The money reached the receiver: this is the moment the transfer happened.
                outbox.enqueue(WalletEvents.TRANSFER_COMPLETED, source.id(), new WalletEvents.TransferCompleted(
                        tx.id(), WalletEvents.Wallet.of(source), WalletEvents.Wallet.of(destination), hold.amount()));
            }
            return settled;
        });
    }

    private Account holdsAccount(String currency) {
        return ledger.findSystemAccount(HOLDS_ACCOUNT, currency)
                .orElseThrow(() -> new IllegalStateException("no holds account for " + currency));
    }
}
