-- Clan registry colour (World Design stage 1): the map, launcher and in-game territories read
-- the colour from the clan record instead of hard-coded tables. NULL means "use a neutral fallback".
ALTER TABLE clans ADD COLUMN color CHAR(6);
ALTER TABLE clans ADD CONSTRAINT clans_color_format CHECK (color IS NULL OR color ~ '^[0-9A-F]{6}$');

-- Current official colours, same values as minecraft/server/clan-world.json → territories.colors.
UPDATE clans SET color = v.color, updated_at = CURRENT_TIMESTAMP
FROM (VALUES ('SCAM','E84A5F'),('NYA','F47DCB'),('AKUMA','A970FF'),('ZXC','4DA8FF'),('YAKUZA','FFB347')) AS v(code, color)
WHERE clans.code_key = v.code AND clans.color IS NULL;
