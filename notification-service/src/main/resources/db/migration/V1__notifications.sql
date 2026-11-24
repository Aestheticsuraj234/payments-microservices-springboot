-- Adapted from reference/go/db/notification/migrations/000001_notifications.up.sql.

-- One row per email we sent. The primary key is what makes the consumer
-- idempotent: an event delivered twice finds its row and is skipped. One
-- event can notify two people (a transfer's sender and receiver), so the key
-- is the event and the person.
CREATE TABLE notifications (
    event_id   uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    recipient  text        NOT NULL,
    subject    text        NOT NULL,
    sent_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, user_id)
);
