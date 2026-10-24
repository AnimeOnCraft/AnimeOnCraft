-- Small peaceful clan building plots inside Sora. Existing states and ownership stay unchanged.
ALTER TABLE world_regions DROP CONSTRAINT world_regions_kind_check;
ALTER TABLE world_regions ADD CONSTRAINT world_regions_kind_check
  CHECK (kind IN ('CORE','MARKET','STATE','BORDERLAND','RIM','FRONTIER_SLOT','FRONTIER_WILD','SEA'));
ALTER TABLE world_regions DROP CONSTRAINT world_regions_check;
ALTER TABLE world_regions ADD CONSTRAINT world_regions_clan_kind_check
  CHECK (clan_id IS NULL OR kind IN ('STATE','FRONTIER_SLOT','MARKET'));
ALTER TABLE world_regions ADD CONSTRAINT world_regions_market_rules_check CHECK (kind <> 'MARKET' OR
  (clan_id IS NOT NULL AND build_policy='MEMBERS' AND pvp_policy='OFF'
   AND capital_x IS NULL AND capital_z IS NULL AND spawn_x IS NULL AND spawn_z IS NULL));
DROP INDEX world_regions_one_active_per_clan;
CREATE UNIQUE INDEX world_regions_one_active_per_clan ON world_regions(world_id,clan_id)
  WHERE clan_id IS NOT NULL AND status='ACTIVE' AND kind <> 'MARKET';
CREATE UNIQUE INDEX world_regions_one_market_per_clan ON world_regions(world_id,clan_id)
  WHERE kind='MARKET' AND status='ACTIVE';
