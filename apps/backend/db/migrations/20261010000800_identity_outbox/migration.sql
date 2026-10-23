CREATE TABLE outbox_events (
  event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(), type VARCHAR(64) NOT NULL,
  schema_version INTEGER NOT NULL DEFAULT 1 CHECK(schema_version > 0), aggregate_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  aggregate_version BIGINT NOT NULL CHECK(aggregate_version >= 0), occurred_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 16384)
);
CREATE INDEX outbox_events_aggregate ON outbox_events(aggregate_id,aggregate_version);
GRANT SELECT,INSERT ON outbox_events TO anime_app;
-- Consumer delivery/ack/checkpoint state belongs to the later integration stage; events stay append-only.
