-- World Design stage 4: capture engine for strategic points and resource sites (docs/operations/CAPTURE_STAGE4.md).
-- Rules live with the world (imported from capture-rules.toml); running captures keep the state to restore.

ALTER TABLE world_catalog ADD COLUMN capture_rules jsonb
  CHECK (capture_rules IS NULL OR (jsonb_typeof(capture_rules) = 'object' AND octet_length(capture_rules::text) <= 32768));

ALTER TABLE capture_events
  ADD COLUMN server_id uuid REFERENCES game_servers(id) ON DELETE RESTRICT,
  -- The point state before the declaration; a capture without a winner returns to it.
  ADD COLUMN prior_state varchar(10) CHECK (prior_state IS NULL OR prior_state IN ('NEUTRAL','CONTROLLED','PROTECTED','AVAILABLE')),
  ADD COLUMN prior_owner uuid REFERENCES clans(id) ON DELETE RESTRICT,
  ADD COLUMN snapshot_at timestamptz(3),
  ADD COLUMN finished_at timestamptz(3),
  ADD COLUMN end_reason varchar(64) CHECK (end_reason IS NULL OR char_length(btrim(end_reason)) BETWEEN 1 AND 64);
ALTER TABLE capture_events DROP CONSTRAINT IF EXISTS capture_events_outcome_check;
ALTER TABLE capture_events ADD CONSTRAINT capture_events_outcome_check
  CHECK (outcome IS NULL OR outcome IN ('CAPTURED','RENEWED','DEFENDED','NO_WINNER','VOIDED','CANCELLED'));
ALTER TABLE capture_events ADD CONSTRAINT capture_events_terminal
  CHECK ((state IN ('FINISHED','VOIDED','CANCELLED')) = (finished_at IS NOT NULL AND outcome IS NOT NULL));
-- Cooldown and per-clan limits look events up by declaring clan.
CREATE INDEX capture_events_declarer ON capture_events(declared_by_clan, point_id, finished_at);

ALTER TABLE resource_site_history DROP CONSTRAINT IF EXISTS resource_site_history_event_check;
ALTER TABLE resource_site_history ADD CONSTRAINT resource_site_history_event_check
  CHECK (event IN ('IMPORTED','DECLARED','ACTIVE','CAPTURED','RENEWED','DEFENDED','NO_WINNER','VOIDED','PROTECTION_ENDED','LEASE_EXPIRED',
    'NEUTRALIZED','RELEASED','REFRESHED','ADMIN_TRANSFER','ADMIN_RESET','ADMIN_CANCEL','ADMIN_REFRESH','DISABLED','ENABLED'));
CREATE INDEX resource_site_history_recent ON resource_site_history(at DESC) WHERE event IN ('DECLARED','CAPTURED','RENEWED','DEFENDED','NEUTRALIZED','RELEASED','ADMIN_TRANSFER','ADMIN_RESET');
