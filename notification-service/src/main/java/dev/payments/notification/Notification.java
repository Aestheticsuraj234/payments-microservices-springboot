package dev.payments.notification;

import java.util.UUID;

/** One email to one person about one event. */
public record Notification(UUID eventId, UUID userId, String subject, String body) {
}
