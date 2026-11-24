-- Ported from reference/go/db/wallet/migrations/000006_trace_context.up.sql.

-- The trace an event was written in, as a W3C traceparent header
-- ("00-<trace id>-<span id>-01"). The relay publishes the event later, on
-- another thread, and continues that trace, so one trace runs from the HTTP
-- request through Kafka to whatever the event causes.
ALTER TABLE outbox ADD COLUMN traceparent text;
