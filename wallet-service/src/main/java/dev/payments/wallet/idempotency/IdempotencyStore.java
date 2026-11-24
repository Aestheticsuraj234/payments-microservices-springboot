package dev.payments.wallet.idempotency;

import dev.payments.wallet.config.WalletProperties;
import dev.payments.wallet.error.IdempotencyException;
import dev.payments.wallet.error.IdempotencyException.Reason;
import dev.payments.wallet.ledger.RetryingTransactions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work once per idempotency key, and remembers the response, in the
 * {@code idempotency_keys} table.
 *
 * <p>The lifecycle of a key:
 *
 * <pre>
 *   (none) ──claim──▶ in_progress ──work + complete, one transaction──▶ completed ──TTL──▶ (deleted)
 *                         │
 *                         └──work failed──▶ released (deleted), so a fixed request may use the key
 * </pre>
 *
 * <p>The crucial line is "one transaction": the money moves and the key is
 * marked completed in the same database transaction. Either both happen, or
 * neither does. There is no moment where money moved but the key doesn't know.
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate shortTransaction;
    private final RetryingTransactions transactions;
    private final Duration ttl;
    private final Duration lease;

    public IdempotencyStore(JdbcClient jdbc, PlatformTransactionManager txManager,
            RetryingTransactions transactions, WalletProperties properties) {
        this.jdbc = jdbc;
        this.shortTransaction = new TransactionTemplate(txManager);
        this.transactions = transactions;
        this.ttl = properties.idempotency().ttl();
        this.lease = properties.idempotency().lease();
    }

    /**
     * Runs {@code work} once for {@code key}. The first request runs it, inside
     * a database transaction that also stores its response. A retry with the
     * same key and request gets the stored response back, and work doesn't run.
     */
    public StoredResponse run(IdempotencyKey key, Supplier<StoredResponse> work) {
        Optional<StoredResponse> replay = shortTransaction.execute(status -> claim(key));
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return transactions.run(() -> {
                StoredResponse response = work.get();
                complete(key, response);
                return response;
            });
        } catch (RuntimeException e) {
            // The transaction rolled back, so nothing happened. Let go of the
            // key, so the client can fix the request and send it again.
            release(key);
            throw e;
        }
    }

    /**
     * Marks the key as in progress, so this request may run it. If the key
     * already exists, returns the response to replay, or throws the reason
     * this request can't run.
     */
    private Optional<StoredResponse> claim(IdempotencyKey key) {
        // A new key goes in as in progress. If another request is inserting
        // the same key right now, the primary key makes us wait until it commits.
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys (scope, key, fingerprint, expires_at)
                        VALUES (:scope, :key, :fingerprint, now() + make_interval(secs => :ttl))
                        ON CONFLICT (scope, key) DO NOTHING
                        """)
                .param("scope", key.scope())
                .param("key", key.value())
                .param("fingerprint", key.fingerprint())
                .param("ttl", ttl.toSeconds())
                .update();
        return inserted == 1 ? Optional.empty() : existing(key);
    }

    private record Row(byte[] fingerprint, String status, Integer code, byte[] body, boolean abandoned) {
    }

    /** Locks the row of a key that's already there, and decides what to do with this request. */
    private Optional<StoredResponse> existing(IdempotencyKey key) {
        Optional<Row> row = jdbc.sql("""
                        SELECT fingerprint, status, response_code, response_body,
                               locked_at < now() - make_interval(secs => :lease) AS abandoned
                        FROM idempotency_keys
                        WHERE scope = :scope AND key = :key
                        FOR UPDATE
                        """)
                .param("scope", key.scope())
                .param("key", key.value())
                .param("lease", lease.toSeconds())
                .query((rs, n) -> new Row(rs.getBytes(1), rs.getString(2), (Integer) rs.getObject(3),
                        rs.getBytes(4), rs.getBoolean(5)))
                .optional();
        if (row.isEmpty()) {
            // A failed request released it a moment ago. A retry can claim it.
            throw new IdempotencyException(Reason.IN_PROGRESS);
        }
        Row r = row.get();
        if (!MessageDigest.isEqual(r.fingerprint(), key.fingerprint())) {
            throw new IdempotencyException(Reason.KEY_REUSED);
        }
        if (r.status().equals("completed")) {
            return Optional.of(new StoredResponse(r.code(), r.body(), true));
        }
        if (!r.abandoned()) {
            throw new IdempotencyException(Reason.IN_PROGRESS);
        }
        // In progress for longer than any request takes: the request that
        // claimed it died, and its transaction never committed. Take it over.
        jdbc.sql("UPDATE idempotency_keys SET locked_at = now() WHERE scope = :scope AND key = :key")
                .param("scope", key.scope())
                .param("key", key.value())
                .update();
        return Optional.empty();
    }

    /**
     * Completes the key, in the same transaction as the work. The status check
     * proves the key is still ours: if another request completed it first, we
     * throw, the transaction rolls back, and our work never happened.
     */
    private void complete(IdempotencyKey key, StoredResponse response) {
        int updated = jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'completed', response_code = :code, response_body = :body, completed_at = now()
                        WHERE scope = :scope AND key = :key AND status = 'in_progress'
                        """)
                .param("scope", key.scope())
                .param("key", key.value())
                .param("code", response.status())
                .param("body", response.body())
                .update();
        if (updated == 0) {
            throw new IdempotencyException(Reason.IN_PROGRESS);
        }
    }

    /** Deletes the claim after the work failed, so the key can run again. */
    private void release(IdempotencyKey key) {
        try {
            jdbc.sql("DELETE FROM idempotency_keys WHERE scope = :scope AND key = :key AND status = 'in_progress'")
                    .param("scope", key.scope())
                    .param("key", key.value())
                    .update();
        } catch (RuntimeException e) {
            // Not fatal: the lease runs out, and a retry takes the key over.
            log.warn("could not release idempotency key", e);
        }
    }

    /** Deletes keys past their TTL. Returns how many. */
    public int deleteExpired() {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at < now()").update();
    }
}
