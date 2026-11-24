package dev.payments.auth.user;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * For other services only: the gateway has no route to {@code /internal/**}.
 * The notification service asks here where to reach a user. Events carry
 * user ids, never email addresses: an address can change, and events live
 * in Kafka for days.
 */
@RestController
class InternalUserController {

    record UserContact(UUID id, String email) {
    }

    private final AuthService auth;

    InternalUserController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/internal/users/{id}")
    UserContact user(@PathVariable UUID id) {
        User u = auth.user(id);
        return new UserContact(u.id(), u.email());
    }
}
