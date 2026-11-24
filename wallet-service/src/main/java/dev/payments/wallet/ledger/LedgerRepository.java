package dev.payments.wallet.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every SQL statement the ledger runs, and nothing else: no rules live here.
 *
 * <p>We use {@link JdbcClient}, plain SQL with named parameters, instead of
 * JPA. A ledger is append-only and set-based: we insert rows, sum them, and
 * lock them. Writing that SQL ourselves keeps the locking and the invariants
 * in plain sight, and the database does the heavy lifting (the balance check
 * is a trigger, the balance is a view).
 */
@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Account> ACCOUNT = (rs, n) -> new Account(
            rs.getObject("id", UUID.class),
            AccountKind.fromDb(rs.getString("kind")),
            rs.getObject("owner_id", UUID.class),
            rs.getString("name"),
            rs.getString("currency"),
            Direction.fromDb(rs.getString("normal_balance")),
            instant(rs, "created_at"));

    private static final RowMapper<Entry> ENTRY = (rs, n) -> new Entry(
            rs.getLong("id"),
            rs.getObject("transaction_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            Direction.fromDb(rs.getString("direction")),
            rs.getLong("amount"),
            rs.getString("currency"),
            instant(rs, "created_at"));

    /** Opens a customer's wallet. Throws {@code DuplicateKeyException} if they already have one in that currency. */
    public Account createWallet(UUID ownerId, String currency) {
        return jdbc.sql("""
                        INSERT INTO accounts (kind, owner_id, name, currency, normal_balance)
                        VALUES ('wallet', :ownerId, :name, :currency, 'credit')
                        RETURNING *
                        """)
                .param("ownerId", ownerId)
                .param("name", currency + " wallet")
                .param("currency", currency)
                .query(ACCOUNT)
                .single();
    }

    public Optional<Account> findAccount(UUID id) {
        return jdbc.sql("SELECT * FROM accounts WHERE id = :id")
                .param("id", id)
                .query(ACCOUNT)
                .optional();
    }

    public Optional<Account> findSystemAccount(String name, String currency) {
        return jdbc.sql("SELECT * FROM accounts WHERE kind = 'system' AND name = :name AND currency = :currency")
                .param("name", name)
                .param("currency", currency)
                .query(ACCOUNT)
                .optional();
    }

    /**
     * Locks an account's row until the end of the transaction. Anyone else who
     * locks it waits here until we commit or roll back. Must run inside a
     * transaction, or the lock is released as soon as the statement ends.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID accountId) {
        jdbc.sql("SELECT id FROM accounts WHERE id = :id FOR UPDATE")
                .param("id", accountId)
                .query(UUID.class)
                .single();
    }

    /** An account's balance, summed from its entries by the {@code account_balances} view. */
    public long balance(UUID accountId) {
        return jdbc.sql("SELECT balance FROM account_balances WHERE account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    /**
     * Records a transaction and its entries. On its own this is not atomic, so
     * it must join the caller's database transaction: {@code MANDATORY} makes
     * Spring throw if there is none, instead of quietly committing each insert
     * on its own. Postgres checks that the entries balance at commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Transaction post(String description, List<Posting> postings) {
        record Journal(UUID id, Instant createdAt) {
        }
        Journal journal = jdbc.sql("INSERT INTO transactions (description) VALUES (:description) RETURNING id, created_at")
                .param("description", description)
                .query((rs, n) -> new Journal(rs.getObject("id", UUID.class), instant(rs, "created_at")))
                .single();
        UUID txId = journal.id();

        List<Entry> entries = new ArrayList<>(postings.size());
        for (Posting p : postings) {
            entries.add(jdbc.sql("""
                            INSERT INTO entries (transaction_id, account_id, direction, amount, currency)
                            VALUES (:txId, :accountId, CAST(:direction AS entry_direction), :amount, :currency)
                            RETURNING *
                            """)
                    .param("txId", txId)
                    .param("accountId", p.accountId())
                    .param("direction", p.direction().dbValue())
                    .param("amount", p.amount())
                    .param("currency", p.currency())
                    .query(ENTRY)
                    .single());
        }
        return new Transaction(txId, description, journal.createdAt(), List.copyOf(entries));
    }

    /**
     * A page of an account's entries, newest first: those with an id below
     * {@code beforeId}. This is keyset pagination: the index on
     * {@code (account_id, id)} jumps straight to the page, so page 1,000 is as
     * fast as page 1, unlike {@code OFFSET}, which reads and discards every
     * row before the page.
     */
    public List<Entry> entries(UUID accountId, long beforeId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM entries
                        WHERE account_id = :accountId AND id < :beforeId
                        ORDER BY id DESC
                        LIMIT :limit
                        """)
                .param("accountId", accountId)
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query(ENTRY)
                .list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
