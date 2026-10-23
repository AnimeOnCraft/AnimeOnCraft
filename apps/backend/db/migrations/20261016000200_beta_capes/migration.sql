ALTER TABLE game_tickets ADD COLUMN connection_id UUID, ADD COLUMN joined_at TIMESTAMPTZ(3);
CREATE UNIQUE INDEX game_tickets_server_connection ON game_tickets(server_id, connection_id) WHERE connection_id IS NOT NULL;
ALTER TABLE game_tickets ADD CONSTRAINT game_tickets_join_receipt CHECK (joined_at IS NULL OR (consumed_at IS NOT NULL AND connection_id IS NOT NULL AND joined_at >= consumed_at));

CREATE TABLE beta_campaign (
  id VARCHAR(16) PRIMARY KEY CHECK (id = 'beta'),
  starts_at TIMESTAMPTZ(3), ends_at TIMESTAMPTZ(3),
  CHECK ((starts_at IS NULL AND ends_at IS NULL) OR (starts_at IS NOT NULL AND ends_at = starts_at + INTERVAL '7 days'))
);
INSERT INTO beta_campaign(id) VALUES ('beta');
CREATE TABLE player_capes (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  cape_id VARCHAR(32) NOT NULL CHECK (cape_id = 'beta'),
  awarded_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  equipped BOOLEAN NOT NULL DEFAULT false,
  PRIMARY KEY(user_id, cape_id)
);
CREATE UNIQUE INDEX player_capes_one_equipped ON player_capes(user_id) WHERE equipped;
GRANT SELECT ON beta_campaign TO anime_app;
GRANT SELECT, INSERT, UPDATE ON player_capes TO anime_app;
