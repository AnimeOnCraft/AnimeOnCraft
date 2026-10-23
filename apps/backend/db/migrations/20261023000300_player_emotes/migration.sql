-- Cosmetic ownership is account-bound. No world or existing progress is modified.
CREATE TABLE player_emote_grants (
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 code varchar(32) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
 source varchar(80) NOT NULL,
 granted_at timestamptz(3) NOT NULL DEFAULT now(),
 PRIMARY KEY (user_id,code)
);
INSERT INTO player_emote_grants(user_id,code,source)
SELECT id,code,'starter' FROM users CROSS JOIN
 (VALUES ('NEON_DANCE'),('HERO_POSE'),('ZEN_POSE')) AS starter(code)
ON CONFLICT DO NOTHING;
GRANT SELECT,INSERT,UPDATE,DELETE ON player_emote_grants TO anime_app;
