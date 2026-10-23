BEGIN;
-- Separate from Microsoft ownership proofs: this is an AnimeOnCraft game identity.
CREATE TABLE game_profiles (
  user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE RESTRICT,
  game_name varchar(16) NOT NULL,
  game_name_key varchar(16) GENERATED ALWAYS AS (lower(game_name COLLATE "C")) STORED,
  minecraft_uuid uuid NOT NULL,
  created_at timestamptz(3) NOT NULL DEFAULT now(),
  CONSTRAINT game_profiles_name_format CHECK (game_name ~ '^[A-Za-z0-9_]{3,16}$'),
  CONSTRAINT game_profiles_game_name_key_key UNIQUE (game_name_key),
  CONSTRAINT game_profiles_minecraft_uuid_key UNIQUE (minecraft_uuid)
);
-- No runtime rename/delete: changing the spelling would change offline player saves.
GRANT SELECT, INSERT ON game_profiles TO anime_app;
COMMIT;
