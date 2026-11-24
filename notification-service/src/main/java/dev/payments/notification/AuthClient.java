package dev.payments.notification;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** The auth service's internal API: where to reach a user. */
@HttpExchange("/internal")
public interface AuthClient {

    record UserContact(UUID id, String email) {
    }

    @GetExchange("/users/{id}")
    UserContact user(@PathVariable UUID id);
}
