-- Gacha: account-bound cosmetic ownership, pity counters and an append-only roll history.
-- Items are only obtainable by rolling (owner decision 2026-10-25); there is no shop table on purpose.
CREATE TABLE player_cosmetic_grants (
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 code varchar(32) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
 slot varchar(8) NOT NULL CHECK (slot IN ('HEAD','FACE','BELT','BACK','TITLE','EMOTE')),
 source varchar(80) NOT NULL,
 granted_at timestamptz(3) NOT NULL DEFAULT now(),
 PRIMARY KEY (user_id,code)
);
CREATE TABLE gacha_pity (
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 banner varchar(32) NOT NULL,
 pity5 integer NOT NULL DEFAULT 0 CHECK (pity5 >= 0),
 pity4 integer NOT NULL DEFAULT 0 CHECK (pity4 >= 0),
 total integer NOT NULL DEFAULT 0 CHECK (total >= 0),
 PRIMARY KEY (user_id,banner)
);
CREATE TABLE gacha_rolls (
 id bigserial PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 banner varchar(32) NOT NULL,
 banner_version integer NOT NULL,
 request_id uuid NOT NULL,
 idx smallint NOT NULL CHECK (idx BETWEEN 0 AND 9),
 rolled_tier smallint NOT NULL CHECK (rolled_tier IN (0,3,4,5)),
 item_code varchar(32),
 pity5_before integer NOT NULL,
 pity4_before integer NOT NULL,
 paid_crystals integer NOT NULL CHECK (paid_crystals >= 0),
 created_at timestamptz(3) NOT NULL DEFAULT now(),
 UNIQUE (user_id,request_id,idx)
);
CREATE INDEX gacha_rolls_user_created ON gacha_rolls(user_id,created_at DESC);
GRANT SELECT,INSERT ON player_cosmetic_grants TO anime_app;
GRANT SELECT,INSERT,UPDATE ON gacha_pity TO anime_app;
GRANT SELECT,INSERT ON gacha_rolls TO anime_app;
GRANT USAGE ON SEQUENCE gacha_rolls_id_seq TO anime_app;
