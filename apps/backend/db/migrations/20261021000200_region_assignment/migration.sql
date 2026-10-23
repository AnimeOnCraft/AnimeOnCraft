-- World Design stage 8: a frontier slot assigned to a new clan from the admin panel (docs/FRONTIER_EXPANSION.md).
-- 'ADMIN' rows keep their clan, status and policies across catalog re-imports; everything else follows the TOML.
ALTER TABLE world_regions ADD COLUMN assignment varchar(8) NOT NULL DEFAULT 'CATALOG' CHECK (assignment IN ('CATALOG','ADMIN'));
ALTER TABLE world_regions ADD CONSTRAINT world_regions_admin_slot CHECK (assignment = 'CATALOG' OR kind = 'FRONTIER_SLOT');
-- One active territory per clan: a clan that already has a state cannot also take a slot.
CREATE UNIQUE INDEX world_regions_one_active_per_clan ON world_regions(world_id, clan_id) WHERE clan_id IS NOT NULL AND status = 'ACTIVE';
