-- PvP arena in Sora: a few earned crystals per win, capped per week in crystals.ts (owner decision pending, recommended option).
-- Only a new ledger kind; (user, kind, ref) uniqueness keeps one payment per player and match. No existing data is changed.
ALTER TABLE premium_ledger DROP CONSTRAINT premium_ledger_kind_check;
ALTER TABLE premium_ledger ADD CONSTRAINT premium_ledger_kind_check
 CHECK (kind IN ('LEVEL','GUILD','GUILD_TEAM','DUNGEON','ARENA','PASS','PURCHASE','ROLL','REFUND','ADMIN'));
