BEGIN;
CREATE TABLE player_skins (
 user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 png bytea NOT NULL CHECK (octet_length(png) BETWEEN 50 AND 24000),
 model varchar(7) NOT NULL CHECK (model IN ('classic','slim')),
 updated_at timestamptz(3) NOT NULL DEFAULT now()
);
GRANT SELECT,INSERT,UPDATE,DELETE ON player_skins TO anime_app;
COMMIT;
