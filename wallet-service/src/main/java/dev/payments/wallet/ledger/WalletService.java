package dev.payments.wallet.ledger;

import dev.payments.wallet.config.WalletProperties;
import dev.payments.wallet.error.ConflictException;
import dev.payments.wallet.error.FieldErrors;
import dev.payments.wallet.error.NotFoundException;
import dev.payments.wallet.error.RuleViolationException;
import dev.payments.wallet.money.Money;
import dev.payments.wallet.money.MoneyRules;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The wallet's business rules. It validates input, enforces the rules, and uses
 * the {@link LedgerRepository} to persist the results. It knows nothing about
 * HTTP, so every way into the service gets the same rules.
 */
@Service
public class WalletService {

    /** The system account that funds deposits: the money we hold at our bank, one per currency. */
    static final String SETTLEMENT = "settlement";

    /** The stable code clients get when a wallet can't cover a transfer. */
    public static final String INSUFFICIENT_FUNDS = "insufficient_funds";

    private final LedgerRepository ledger;
    private final MoneyRules moneyRules;
    private final WalletProperties properties;
    private final RetryingTransactions transactions;

    public WalletService(LedgerRepository ledger, MoneyRules moneyRules, WalletProperties properties,
            RetryingTransactions transactions) {
        this.ledger = ledger;
        this.moneyRules = moneyRules;
        this.properties = properties;
        this.transactions = transactions;
    }

    /** Opens a wallet for an owner, in one currency. An owner can have one wallet per currency. */
    @Transactional
    public Account createWallet(UUID ownerId, String currency) {
        var errors = new FieldErrors();
        if (ownerId == null) {
            errors.add("owner_id", "must be a UUID");
        }
        moneyRules.checkCurrency(errors, "currency", currency);
        errors.throwIfAny();

        // We support a currency when we have a settlement account to fund it.
        if (ledger.findSystemAccount(SETTLEMENT, currency).isEmpty()) {
            errors.add("currency", "unsupported currency").throwIfAny();
        }
        try {
            return ledger.createWallet(ownerId, currency);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("owner already has a wallet in " + currency);
        }
    }

    /**
     * The caller's wallet with the given id. System accounts are ours, and
     * other people's wallets are theirs: to this caller they don't exist, so
     * they are "not found" rather than "forbidden", which would confirm that
     * the id is real.
     */
    @Transactional(readOnly = true)
    public Account wallet(UUID caller, UUID id) {
        return ledger.findAccount(id)
                .filter(Account::isWallet)
                .filter(a -> caller.equals(a.ownerId()))
                .orElseThrow(() -> new NotFoundException("wallet not found"));
    }

    /** A wallet's balance, derived from its entries. */
    @Transactional(readOnly = true)
    public Money balance(UUID caller, UUID walletId) {
        Account wallet = wallet(caller, walletId);
        return new Money(ledger.balance(wallet.id()), wallet.currency());
    }

    /**
     * Adds money from outside to a wallet, as one balanced transaction: a debit
     * to the settlement account, which now holds the money at our bank, and a
     * credit to the wallet, which now owes it to the customer.
     */
    @Transactional
    public Transaction deposit(UUID caller, UUID walletId, Money amount) {
        var errors = new FieldErrors();
        moneyRules.checkAmount(errors, "amount", amount.amount());
        Account wallet = wallet(caller, walletId);
        // The client must say which currency it means, so 500 can never
        // quietly become 500 yen in a dollar wallet.
        if (!wallet.currency().equals(amount.currency())) {
            errors.add("currency", "must match the wallet's currency, " + wallet.currency());
        }
        errors.throwIfAny();

        // A missing settlement account is our bug, not the client's: a 500.
        Account settlement = ledger.findSystemAccount(SETTLEMENT, wallet.currency())
                .orElseThrow(() -> new IllegalStateException("no settlement account for " + wallet.currency()));

        return ledger.post("deposit", List.of(
                Posting.debit(settlement, amount.amount()),
                Posting.credit(wallet, amount.amount())));
    }

    /**
     * Moves money between two wallets in the same currency, as one balanced
     * transaction: a debit to the sender, a credit to the receiver.
     *
     * <p>Validation reads the wallets without locks: accounts never change
     * currency or owner. Only the balance can change under us, so the balance
     * check runs inside the transaction, after both wallets are locked.
     */
    public Transfer transfer(UUID caller, String fromId, String toId, Money amount) {
        var errors = new FieldErrors();
        moneyRules.checkAmount(errors, "amount", amount.amount());
        // Money leaves only the caller's own wallets, and may go to anyone's.
        Account from = walletField(errors, "from", fromId, caller);
        Account to = walletField(errors, "to", toId, null);
        if (from != null && to != null) {
            if (from.id().equals(to.id())) {
                errors.add("to", "must be a different wallet");
            } else if (!to.currency().equals(from.currency())) {
                errors.add("to", "must be a " + from.currency() + " wallet, like from");
            } else if (!from.currency().equals(amount.currency())) {
                errors.add("currency", "must match the wallets' currency, " + from.currency());
            }
        }
        errors.throwIfAny();

        Transaction tx = transactions.run(() -> {
            // Lock both wallets until we commit. Another transfer touching
            // either one waits right here, so the balance below stays true.
            lockInOrder(from.id(), to.id());
            if (ledger.balance(from.id()) < amount.amount()) {
                throw new RuleViolationException(INSUFFICIENT_FUNDS, "insufficient funds");
            }
            return ledger.post("transfer", List.of(
                    Posting.debit(from, amount.amount()),
                    Posting.credit(to, amount.amount())));
        });
        return new Transfer(tx.id(), from.id(), to.id(), amount, tx.createdAt());
    }

    /**
     * Locks two accounts, the lower id first. Every caller takes locks in the
     * same order, so two transactions can never each hold the lock the other
     * is waiting for: no deadlocks. (Any fixed total order works; we use
     * {@link UUID#compareTo}.)
     */
    private void lockInOrder(UUID a, UUID b) {
        if (a.compareTo(b) > 0) {
            UUID swap = a;
            a = b;
            b = swap;
        }
        ledger.lock(a);
        ledger.lock(b);
    }

    /**
     * Loads the wallet a request field names, owned by {@code owner} unless
     * that is null. A malformed or unknown id is a problem with that field,
     * recorded in {@code errors}: the result is null.
     */
    private Account walletField(FieldErrors errors, String field, String id, UUID owner) {
        try {
            return ledger.findAccount(UUID.fromString(id))
                    .filter(Account::isWallet)
                    .filter(a -> owner == null || owner.equals(a.ownerId()))
                    .orElseGet(() -> {
                        errors.add(field, "no such wallet");
                        return null;
                    });
        } catch (IllegalArgumentException | NullPointerException e) {
            errors.add(field, "must be a wallet ID");
            return null;
        }
    }

    /** A page of a wallet's entries, newest first. A null cursor starts at the newest entry. */
    @Transactional(readOnly = true)
    public EntryPage entries(UUID caller, UUID walletId, String cursor, Integer limit) {
        int size = limit == null ? properties.pageSize().defaultSize() : limit;
        var errors = new FieldErrors();
        if (size < 1 || size > properties.pageSize().max()) {
            errors.add("limit", "must be between 1 and " + properties.pageSize().max());
        }
        OptionalLong before = Cursor.decode(cursor);
        if (before.isEmpty()) {
            errors.add("cursor", "invalid cursor");
        }
        errors.throwIfAny();
        wallet(caller, walletId);

        // Fetch one more than the page holds. If it comes back, there is
        // another page, and we know without counting anything.
        List<Entry> entries = ledger.entries(walletId, before.getAsLong(), size + 1);
        if (entries.size() <= size) {
            return new EntryPage(entries, null);
        }
        List<Entry> page = entries.subList(0, size);
        return new EntryPage(List.copyOf(page), Cursor.encode(page.getLast().id()));
    }
}
