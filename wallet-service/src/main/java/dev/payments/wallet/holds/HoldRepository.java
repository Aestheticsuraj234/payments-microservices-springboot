package dev.payments.wallet.holds;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class HoldRepository {

    private static final RowMapper<Hold> HOLD = (rs, n) -> new Hold(
            rs.getObject("id", UUID.class),
            rs.getObject("wallet_id", UUID.class),
            rs.getLong("amount"),
            rs.getString("currency"),
            Hold.Status.fromDb(rs.getString("status")),
            rs.getObject("settled_to", UUID.class));

    private final JdbcClient jdbc;

    HoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Hold> find(UUID id) {
        return jdbc.sql("SELECT * FROM holds WHERE id = :id").param("id", id).query(HOLD).optional();
    }

    /** Locks the hold until the end of the transaction: two settlements of one hold take turns. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Hold> lock(UUID id) {
        return jdbc.sql("SELECT * FROM holds WHERE id = :id FOR UPDATE").param("id", id).query(HOLD).optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Hold create(UUID id, UUID walletId, long amount, String currency, UUID holdTxId) {
        return jdbc.sql("""
                        INSERT INTO holds (id, wallet_id, amount, currency, hold_tx_id)
                        VALUES (:id, :walletId, :amount, :currency, :txId)
                        RETURNING *
                        """)
                .param("id", id).param("walletId", walletId).param("amount", amount)
                .param("currency", currency).param("txId", holdTxId)
                .query(HOLD).single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Hold settle(UUID id, Hold.Status status, UUID settledTo, UUID settleTxId) {
        return jdbc.sql("""
                        UPDATE holds
                        SET status = CAST(:status AS hold_status), settled_to = :to, settle_tx_id = :txId,
                            settled_at = now()
                        WHERE id = :id
                        RETURNING *
                        """)
                .param("id", id).param("status", status.dbValue()).param("to", settledTo).param("txId", settleTxId)
                .query(HOLD).single();
    }
}
