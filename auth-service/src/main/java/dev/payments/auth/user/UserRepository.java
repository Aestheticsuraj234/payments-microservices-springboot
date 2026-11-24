package dev.payments.auth.user;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

    /** A user and their password hash: only the service sees this. */
    record UserWithHash(User user, String passwordHash) {
    }

    private static final RowMapper<User> USER = (rs, n) -> new User(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws {@code DuplicateKeyException} if the email is taken, in any letter case. */
    User create(String email, String passwordHash) {
        return jdbc.sql("INSERT INTO users (email, password_hash) VALUES (:email, :hash) RETURNING *")
                .param("email", email)
                .param("hash", passwordHash)
                .query(USER)
                .single();
    }

    Optional<UserWithHash> findByEmail(String email) {
        // citext: the comparison ignores letter case.
        return jdbc.sql("SELECT * FROM users WHERE email = CAST(:email AS citext)")
                .param("email", email)
                .query((rs, n) -> new UserWithHash(USER.mapRow(rs, n), rs.getString("password_hash")))
                .optional();
    }

    Optional<User> find(UUID id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id").param("id", id).query(USER).optional();
    }
}
