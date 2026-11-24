package dev.payments.transfer.web;

import java.util.UUID;

/**
 * The user a request comes from, as the gateway authenticated them. A
 * controller asks for one by declaring a {@code Caller} parameter, and
 * {@link CallerArgumentResolver} fills it in.
 */
public record Caller(UUID userId) {
}
