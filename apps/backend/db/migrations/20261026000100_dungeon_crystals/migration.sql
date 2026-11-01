-- Rift Gate: crystals for the first clear of each dungeon difficulty (owner decision 2026-10-26).
-- Only a new ledger kind; the (user, kind, ref) uniqueness keeps one payment per player, dungeon and difficulty.
ALTER TABLE premium_ledger DROP CONSTRAINT premium_ledger_kind_check;
ALTER TABLE premium_ledger ADD CONSTRAINT premium_ledger_kind_check
 CHECK (kind IN ('LEVEL','GUILD','GUILD_TEAM','DUNGEON','PASS','PURCHASE','ROLL','REFUND','ADMIN'));
