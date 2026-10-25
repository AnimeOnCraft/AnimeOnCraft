-- Duplicates (owner decision 2026-10-25): a repeated item gives fragments, fragments buy rolls.
-- Fragments are account-wide (they carry over to later banners) and are never sold or converted to crystals.
CREATE TABLE gacha_fragments (
 user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 balance integer NOT NULL DEFAULT 0 CHECK (balance >= 0),
 updated_at timestamptz(3) NOT NULL DEFAULT now()
);
-- drought: rolls in a row without a new item; it drives the "new item" guarantee.
ALTER TABLE gacha_pity ADD COLUMN drought integer NOT NULL DEFAULT 0 CHECK (drought >= 0);
-- Roll history keeps what each pull gave and how it was paid, so a replay returns the same result.
ALTER TABLE gacha_rolls
 ADD COLUMN item_tier smallint NOT NULL DEFAULT 0 CHECK (item_tier IN (0,3,4,5)),
 ADD COLUMN duplicate boolean NOT NULL DEFAULT false,
 ADD COLUMN fragments_awarded integer NOT NULL DEFAULT 0 CHECK (fragments_awarded >= 0),
 ADD COLUMN fragments_spent integer NOT NULL DEFAULT 0 CHECK (fragments_spent >= 0),
 ADD COLUMN drought_before integer NOT NULL DEFAULT 0 CHECK (drought_before >= 0);
GRANT SELECT,INSERT,UPDATE ON gacha_fragments TO anime_app;
