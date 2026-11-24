package dev.payments.wallet.ledger;

import dev.payments.wallet.config.WalletProperties;
import dev.payments.wallet.error.ConflictException;
import dev.payments.wallet.error.FieldErrors;
import dev.payments.wallet.error.NotFoundException;
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

    private final LedgerRepository ledger;
    private final MoneyRules moneyRules;
    private final WalletProperties properties;

    public WalletService(LedgerRepository ledger, MoneyRules moneyRules, WalletProperties properties) {
        this.ledger = ledger;
        this.moneyRules = moneyRules;
        this.properties = properties;
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
     * The wallet with the given id. System accounts are ours: to a client,
     * they don't exist, so they are "not found" rather than "forbidden".
     */
    @Transactional(readOnly = true)
    public Account wallet(UUID id) {
        return ledger.findAccount(id)
                .filter(Account::isWallet)
                .orElseThrow(() -> new NotFoundException("wallet not found"));
    }

    /** A wallet's balance, derived from its entries. */
    @Transactional(readOnly = true)
    public Money balance(UUID walletId) {
        Account wallet = wallet(walletId);
        return new Money(ledger.balance(wallet.id()), wallet.currency());
    }

    /**
     * Adds money from outside to a wallet, as one balanced transaction: a debit
     * to the settlement account, which now holds the money at our bank, and a
     * credit to the wallet, which now owes it to the customer.
     */
    @Transactional
    public Transaction deposit(UUID walletId, Money amount) {
        var errors = new FieldErrors();
        moneyRules.checkAmount(errors, "amount", amount.amount());
        Account wallet = wallet(walletId);
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

    /** A page of a wallet's entries, newest first. A null cursor starts at the newest entry. */
    @Transactional(readOnly = true)
    public EntryPage entries(UUID walletId, String cursor, Integer limit) {
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
        wallet(walletId);

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
