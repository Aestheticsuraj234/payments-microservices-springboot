-- Ported from reference/go/db/wallet/migrations/000004_outbox.up.sql.

-- Events waiting to be published. The wallet writes them in the same
-- transaction as the change they describe, and the relay publishes them.
CREATE TABLE outbox (
    -- the order events were written in, and the order we publish them in
    seq          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- the event's own id: consumers use it to drop duplicates
    event_id     uuid        NOT NULL UNIQUE,
    topic        text        NOT NULL,
    -- the Kafka message key: events with the same key keep their order
    message_key  text        NOT NULL,
    -- json keeps our bytes exactly as we wrote them; jsonb would reorder keys
    payload      json        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

-- The relay only looks for events it hasn't published, oldest first. Once
-- published, a row leaves this index, so it stays tiny.
CREATE INDEX outbox_unpublished_idx ON outbox (seq) WHERE published_at IS NULL;
