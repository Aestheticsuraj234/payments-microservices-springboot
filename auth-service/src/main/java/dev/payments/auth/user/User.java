package dev.payments.auth.user;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered user. It has no password hash on purpose: nothing outside the
 * repository ever needs it, so nothing can leak it into a response or a log.
 */
public record User(UUID id, String email, Instant createdAt) {
}
