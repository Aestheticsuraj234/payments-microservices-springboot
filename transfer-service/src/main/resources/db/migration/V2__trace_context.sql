-- Ported from reference/go/db/transfer/migrations/000003_trace_context.up.sql.

-- The trace of the request that created the transfer, as a W3C traceparent.
-- The orchestrator runs each step later, on its own thread, and continues
-- this trace, so a transfer is one trace from POST to completion.
ALTER TABLE transfers ADD COLUMN traceparent text;
