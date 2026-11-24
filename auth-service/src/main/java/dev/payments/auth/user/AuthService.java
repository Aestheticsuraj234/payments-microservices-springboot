package dev.payments.auth.user;

import dev.payments.auth.AuthProperties;
import dev.payments.auth.error.ConflictException;
import dev.payments.auth.error.FieldErrors;
import dev.payments.auth.error.NotFoundException;
import dev.payments.auth.error.UnauthenticatedException;
import dev.payments.auth.token.AccessTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Users, logins, and refresh token rotation. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Login and refresh failures say as little as possible. */
    private static final String INVALID_CREDENTIALS = "invalid email or password";
    private static final String INVALID_REFRESH_TOKEN = "invalid refresh token";

    /** Good enough to catch typos; the real test of an email is sending one. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final AccessTokens accessTokens;
    private final AuthProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    /** What a login checks the password against when the email isn't registered. */
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwords,
            AccessTokens accessTokens, AuthProperties properties, PlatformTransactionManager txManager, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.transactions = new TransactionTemplate(txManager);
        this.clock = clock;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    /** Creates a user. Emails are unique without regard to letter case. */
    public User register(@Nullable String email, @Nullable String password) {
        String trimmed = email == null ? "" : email.strip();
        var errors = new FieldErrors();
        if (trimmed.length() > 254 || !EMAIL.matcher(trimmed).matches()) {
            errors.add("email", "must be an email address, like alice@example.com");
        }
        // Length is what makes a password strong. No rules about digits or symbols.
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (length < 12 || length > 128) {
            errors.add("password", "must be 12 to 128 characters long");
        }
        errors.throwIfAny();

        try {
            return users.create(trimmed, passwords.encode(password));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("email already registered");
        }
    }

    /**
     * Checks a user's email and password, and starts a session: a new refresh
     * token family. A wrong password and an unknown email look exactly the
     * same, and take as long, so nobody can find out who has an account.
     */
    public Tokens login(@Nullable String email, @Nullable String password) {
        String pw = password == null ? "" : password;
        var found = users.findByEmail(email == null ? "" : email.strip());
        if (found.isEmpty()) {
            passwords.matches(pw, dummyHash); // hash anyway: same timing as a wrong password
            throw new UnauthenticatedException(INVALID_CREDENTIALS);
        }
        if (!passwords.matches(pw, found.get().passwordHash())) {
            throw new UnauthenticatedException(INVALID_CREDENTIALS);
        }
        UUID userId = found.get().user().id();
        return transactions.execute(status -> issue(userId, UUID.randomUUID(), null));
    }

    private sealed interface Refreshed {
        record Issued(Tokens tokens) implements Refreshed {
        }

        record Invalid() implements Refreshed {
        }

        record Reused(UUID userId, UUID familyId, int revoked) implements Refreshed {
        }
    }

    /**
     * Trades a refresh token for a new pair, and retires the old one, so each
     * refresh token works once. If a retired token comes back, two parties
     * hold copies of it and we can't tell which is the thief: we revoke the
     * whole family, and the user must log in again.
     */
    public Tokens refresh(@Nullable String token) {
        byte[] hash = hash(token == null ? "" : token);
        // The revocation must commit even though the request fails, so the
        // transaction returns an outcome instead of throwing from inside.
        Refreshed outcome = transactions.execute(status -> {
            var found = refreshTokens.findForUpdate(hash);
            if (found.isEmpty()) {
                return new Refreshed.Invalid();
            }
            var old = found.get();
            if (old.revoked() || !old.expiresAt().isAfter(clock.instant())) {
                return new Refreshed.Invalid();
            }
            if (old.rotated()) {
                return new Refreshed.Reused(old.userId(), old.familyId(), refreshTokens.revokeFamily(old.familyId()));
            }
            refreshTokens.rotate(old.id());
            return new Refreshed.Issued(issue(old.userId(), old.familyId(), old.id()));
        });
        return switch (outcome) {
            case Refreshed.Issued issued -> issued.tokens();
            case Refreshed.Invalid invalid -> throw new UnauthenticatedException(INVALID_REFRESH_TOKEN);
            case Refreshed.Reused reused -> {
                log.warn("refresh token reused, family revoked: user_id={} family_id={} revoked={}",
                        reused.userId(), reused.familyId(), reused.revoked());
                throw new UnauthenticatedException(INVALID_REFRESH_TOKEN);
            }
        };
    }

    /**
     * Ends the session the token belongs to, by revoking its family. An
     * unknown token is not an error: the session is over either way. Access
     * tokens already issued keep working until they expire.
     */
    public void logout(@Nullable String token) {
        refreshTokens.revokeFamilyOf(hash(token == null ? "" : token));
    }

    public User user(UUID id) {
        return users.find(id).orElseThrow(() -> new NotFoundException("user not found"));
    }

    /** A new pair of tokens; must run in a transaction. {@code parent} is the token it replaces. */
    private Tokens issue(UUID userId, UUID familyId, @Nullable UUID parent) {
        Instant now = clock.instant();
        String access = accessTokens.issue(userId, now);
        String refresh = newRefreshToken();
        refreshTokens.create(userId, hash(refresh), familyId, parent, now.plus(properties.refreshTokenTtl()));
        return new Tokens(access, accessTokens.ttl(), refresh);
    }

    /** 256 random bits: unguessable, and meaningless to anyone but us. */
    private static String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** We store SHA-256 of each refresh token, so a leaked table holds no working tokens. */
    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
