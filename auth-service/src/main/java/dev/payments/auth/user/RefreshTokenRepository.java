package dev.payments.auth.user;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class RefreshTokenRepository {

    record StoredToken(UUID id, UUID userId, UUID familyId, boolean rotated, boolean revoked, Instant expiresAt) {
    }

    private final JdbcClient jdbc;

    RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void create(UUID userId, byte[] tokenHash, UUID familyId, @Nullable UUID parentId, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO refresh_tokens (user_id, token_hash, family_id, parent_id, expires_at)
                        VALUES (:userId, :hash, :familyId, :parentId, :expiresAt)
                        """)
                .param("userId", userId)
                .param("hash", tokenHash)
                .param("familyId", familyId)
                .param("parentId", parentId)
                .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .update();
    }

    /** Locks the token's row: two refreshes with the same token take turns. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<StoredToken> findForUpdate(byte[] tokenHash) {
        return jdbc.sql("SELECT * FROM refresh_tokens WHERE token_hash = :hash FOR UPDATE")
                .param("hash", tokenHash)
                .query((rs, n) -> new StoredToken(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class),
                        rs.getObject("rotated_at") != null,
                        rs.getObject("revoked_at") != null,
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    void rotate(UUID id) {
        jdbc.sql("UPDATE refresh_tokens SET rotated_at = now() WHERE id = :id").param("id", id).update();
    }

    int revokeFamily(UUID familyId) {
        return jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = :f AND revoked_at IS NULL")
                .param("f", familyId)
                .update();
    }

    /** Revokes the family of the token with this hash: a logout. */
    int revokeFamilyOf(byte[] tokenHash) {
        return jdbc.sql("""
                        UPDATE refresh_tokens SET revoked_at = now()
                        WHERE revoked_at IS NULL
                          AND family_id = (SELECT t.family_id FROM refresh_tokens t WHERE t.token_hash = :hash)
                        """)
                .param("hash", tokenHash)
                .update();
    }
}
