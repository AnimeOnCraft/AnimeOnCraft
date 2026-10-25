-- Premium crystals (gacha currency). Separate from Lightman's coins: nothing here touches the world economy.
-- Balances are split into earned and paid buckets; earned are spent first so refunds of unspent paid crystals stay exact.
-- The ledger is append-only for the app role (no UPDATE/DELETE grant) and (user, kind, ref) is unique,
-- so a retried request never credits twice. No existing data is changed.
CREATE TABLE premium_wallets (
 user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 earned integer NOT NULL DEFAULT 0 CHECK (earned >= 0),
 paid integer NOT NULL DEFAULT 0 CHECK (paid >= 0),
 level_rewarded smallint CHECK (level_rewarded BETWEEN 1 AND 100),
 guild_week varchar(10) NOT NULL DEFAULT '',
 guild_week_earned integer NOT NULL DEFAULT 0 CHECK (guild_week_earned >= 0),
 updated_at timestamptz(3) NOT NULL DEFAULT now()
);
CREATE TABLE premium_ledger (
 id bigserial PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 kind varchar(16) NOT NULL CHECK (kind IN ('LEVEL','GUILD','GUILD_TEAM','PASS','PURCHASE','ROLL','REFUND','ADMIN')),
 ref varchar(120) NOT NULL CHECK (ref ~ '^[A-Za-z0-9_.:-]{1,120}$'),
 delta_earned integer NOT NULL,
 delta_paid integer NOT NULL,
 earned_after integer NOT NULL CHECK (earned_after >= 0),
 paid_after integer NOT NULL CHECK (paid_after >= 0),
 created_at timestamptz(3) NOT NULL DEFAULT now(),
 UNIQUE (user_id, kind, ref)
);
CREATE INDEX premium_ledger_user_created ON premium_ledger(user_id, created_at DESC);
GRANT SELECT,INSERT,UPDATE ON premium_wallets TO anime_app;
GRANT SELECT,INSERT ON premium_ledger TO anime_app;
GRANT USAGE ON SEQUENCE premium_ledger_id_seq TO anime_app;
