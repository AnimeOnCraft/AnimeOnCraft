CREATE TABLE clan_server_presence (
  server_id uuid PRIMARY KEY REFERENCES game_servers(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  snapshot_at timestamptz(3) NOT NULL,
  updated_at timestamptz(3) NOT NULL DEFAULT now()
);
GRANT SELECT,INSERT,UPDATE ON clan_server_presence TO anime_app;
