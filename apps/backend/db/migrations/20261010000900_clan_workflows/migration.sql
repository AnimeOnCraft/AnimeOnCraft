-- Existing shared role capabilities become assignment capabilities without widening access.
ALTER TABLE clan_manager_assignments ADD COLUMN can_remove_members BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE clan_manager_assignments AS assignment SET can_remove_members = role.can_remove_members
FROM clan_roles AS role WHERE assignment.role_id = role.id;

CREATE TABLE idempotency_keys (
  principal_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  route VARCHAR(256) NOT NULL CHECK (route LIKE '/api/v1/%'),
  key UUID NOT NULL,
  request_digest CHAR(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
  result JSONB NOT NULL CHECK (jsonb_typeof(result) = 'object' AND octet_length(result::text) <= 65536),
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMPTZ(3) NOT NULL CHECK (expires_at > created_at),
  PRIMARY KEY(principal_id, route, key)
);
CREATE INDEX idempotency_keys_expiry ON idempotency_keys(expires_at);
GRANT SELECT, INSERT, UPDATE ON TABLE idempotency_keys TO anime_app;
