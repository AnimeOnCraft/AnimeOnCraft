-- Gacha back accessories (e.g. SAKURA_WINGS) share the single back slot with capes and the beta mantle.
-- The application checks the code against the banner catalog and player_cosmetic_grants ownership.
ALTER TABLE player_back_equipment DROP CONSTRAINT player_back_equipment_valid;
ALTER TABLE player_back_equipment ADD CONSTRAINT player_back_equipment_valid CHECK (
    (kind = 'NONE' AND code = '') OR
    (kind = 'CAPE' AND code ~ '^[a-z][a-z0-9-]{0,31}$') OR
    (kind = 'BETA' AND code = 'RIFT_MANTLE') OR
    (kind = 'GACHA' AND code ~ '^[A-Z][A-Z0-9_]{2,31}$')
);
