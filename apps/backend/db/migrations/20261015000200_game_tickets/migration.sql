BEGIN;
CREATE TABLE game_tickets (
  token_hash char(64) PRIMARY KEY CHECK (token_hash ~ '^[a-f0-9]{64}$'),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE RESTRICT,
  server_id uuid NOT NULL REFERENCES game_servers(id) ON DELETE RESTRICT,
  created_at timestamptz(3) NOT NULL DEFAULT now(),
  expires_at timestamptz(3) NOT NULL,
  consumed_at timestamptz(3),
  revoked_at timestamptz(3),
  CHECK (expires_at > created_at AND expires_at <= created_at + interval '60 seconds'),
  CHECK (consumed_at IS NULL OR consumed_at >= created_at),
  CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX game_tickets_user_server ON game_tickets(user_id, server_id);
CREATE UNIQUE INDEX game_tickets_one_pending ON game_tickets(user_id, server_id)
  WHERE consumed_at IS NULL AND revoked_at IS NULL;
GRANT SELECT, INSERT, UPDATE ON game_tickets TO anime_app;
COMMIT;
