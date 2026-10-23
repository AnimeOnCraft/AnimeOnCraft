-- World Design stage 6: relief underlay of the launcher map (docs/MAP_INTEGRATION.md, stage 2 of the map).
-- One top-down image per world, rendered on the owner's PC from the pregenerated world
-- (scripts/world-design/render-map.py) and imported with `world:relief`. Public read only through the active world.

CREATE TABLE world_relief (
  world_id varchar(64) PRIMARY KEY REFERENCES world_catalog(world_id) ON DELETE CASCADE,
  revision bigint NOT NULL DEFAULT 1 CHECK (revision >= 1),
  mime varchar(16) NOT NULL CHECK (mime IN ('image/jpeg','image/png')),
  x0 integer NOT NULL CHECK (x0 BETWEEN -30000000 AND 30000000),
  z0 integer NOT NULL CHECK (z0 BETWEEN -30000000 AND 30000000),
  width_blocks integer NOT NULL CHECK (width_blocks BETWEEN 256 AND 60000),
  height_blocks integer NOT NULL CHECK (height_blocks BETWEEN 256 AND 60000),
  width_px integer NOT NULL CHECK (width_px BETWEEN 64 AND 8192),
  height_px integer NOT NULL CHECK (height_px BETWEEN 64 AND 8192),
  sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  image bytea NOT NULL CHECK (octet_length(image) BETWEEN 1 AND 3145728),
  imported_at timestamptz(3) NOT NULL DEFAULT now()
);

GRANT SELECT,INSERT,UPDATE ON world_relief TO anime_app;
