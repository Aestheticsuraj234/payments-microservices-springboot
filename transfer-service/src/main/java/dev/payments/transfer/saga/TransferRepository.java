package dev.payments.transfer.saga;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TransferRepository {

    /** One try of one step: the saga's history. */
    public record StepRecord(String step, String outcome, String detail, Instant at) {
    }

    private static final RowMapper<Transfer> TRANSFER = (rs, n) -> new Transfer(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getObject("from_wallet_id", UUID.class),
            rs.getObject("to_wallet_id", UUID.class),
            rs.getLong("amount"),
            rs.getString("currency"),
            State.fromDb(rs.getString("state")),
            rs.getString("failure_reason"),
            rs.getInt("attempts"),
            rs.getInt("version"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new transfer, or returns empty if the user already used this key. */
    public Optional<Transfer> create(UUID userId, String key, UUID from, UUID to, long amount, String currency) {
        return jdbc.sql("""
                        INSERT INTO transfers (user_id, idempotency_key, from_wallet_id, to_wallet_id, amount, currency)
                        VALUES (:user, :key, :from, :to, :amount, :currency)
                        ON CONFLICT (user_id, idempotency_key) DO NOTHING
                        RETURNING *
                        """)
                .param("user", userId).param("key", key).param("from", from).param("to", to)
                .param("amount", amount).param("currency", currency)
                .query(TRANSFER).optional();
    }

    public Optional<Transfer> find(UUID id) {
        return jdbc.sql("SELECT * FROM transfers WHERE id = :id").param("id", id).query(TRANSFER).optional();
    }

    public Optional<Transfer> findByKey(UUID userId, String key) {
        return jdbc.sql("SELECT * FROM transfers WHERE user_id = :user AND idempotency_key = :key")
                .param("user", userId).param("key", key).query(TRANSFER).optional();
    }

    public List<StepRecord> steps(UUID transferId) {
        return jdbc.sql("SELECT step, outcome, detail, created_at FROM transfer_steps WHERE transfer_id = :id ORDER BY id")
                .param("id", transferId)
                .query((rs, n) -> new StepRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Leases up to {@code limit} due transfers to the caller: their next
     * attempt moves to the end of the lease, so nobody else claims them
     * meanwhile. If this orchestrator dies, the lease runs out and another one
     * picks them up. SKIP LOCKED lets many orchestrators claim side by side.
     */
    public List<Transfer> claim(int limit, Duration lease) {
        return jdbc.sql("""
                        UPDATE transfers
                        SET next_attempt_at = now() + make_interval(secs => :lease), version = version + 1
                        WHERE id IN (
                            SELECT id FROM transfers
                            WHERE state IN ('pending', 'funds_held', 'compensating')
                              AND next_attempt_at <= now()
                            ORDER BY next_attempt_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING *
                        """)
                .param("lease", lease.toSeconds())
                .param("limit", limit)
                .query(TRANSFER).list();
    }

    /**
     * Moves a transfer to its next state and records the step, if the
     * transfer is still exactly as we read it (same state, same version).
     * Returns false when someone else moved it first: optimistic locking.
     */
    @Transactional
    public boolean advance(Transfer t, State next, String step, String outcome, String reason) {
        if (!t.state().canBecome(next)) {
            throw new IllegalStateException(t.state() + " can't become " + next);
        }
        int updated = jdbc.sql("""
                        UPDATE transfers
                        SET state = :next,
                            failure_reason = CASE WHEN :reason = '' THEN failure_reason ELSE :reason END,
                            attempts = 0, next_attempt_at = now(), version = version + 1, updated_at = now()
                        WHERE id = :id AND state = :state AND version = :version
                        """)
                .param("next", next.dbValue()).param("reason", reason).param("id", t.id())
                .param("state", t.state().dbValue()).param("version", t.version())
                .update();
        if (updated == 1) {
            insertStep(t.id(), step, outcome, reason);
        }
        return updated == 1;
    }

    /** Records a failed try and schedules the next one, if the transfer is still as we read it. */
    @Transactional
    public boolean retryLater(Transfer t, String step, String detail, Instant retryAt) {
        int updated = jdbc.sql("""
                        UPDATE transfers
                        SET attempts = attempts + 1, next_attempt_at = :at, version = version + 1, updated_at = now()
                        WHERE id = :id AND version = :version
                        """)
                .param("at", OffsetDateTime.ofInstant(retryAt, ZoneOffset.UTC))
                .param("id", t.id()).param("version", t.version())
                .update();
        if (updated == 1) {
            insertStep(t.id(), step, "failed", detail);
        }
        return updated == 1;
    }

    private void insertStep(UUID transferId, String step, String outcome, String detail) {
        jdbc.sql("INSERT INTO transfer_steps (transfer_id, step, outcome, detail) VALUES (:id, :step, :outcome, :detail)")
                .param("id", transferId).param("step", step).param("outcome", outcome)
                .param("detail", detail.length() > 500 ? detail.substring(0, 500) : detail)
                .update();
    }
}
