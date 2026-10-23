-- World Design stage 2: data model for regions, strategic points and resource sites.
-- docs/WORLD_DESIGN.md#сущности-бд. The backend is the runtime source of truth; the blueprint TOML in
-- minecraft/server/world-config/ is imported idempotently. Ownership history is append-only (no UPDATE grant).
-- The existing clan_territory_control table keeps serving wars until the capture engine (stage 4) moves to points.

CREATE TABLE world_catalog (
  world_id varchar(64) PRIMARY KEY CHECK (world_id ~ '^[a-z0-9][a-z0-9-]{1,63}$'),
  display_name varchar(80) NOT NULL CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 80),
  active boolean NOT NULL DEFAULT false,
  border_half integer CHECK (border_half IS NULL OR border_half BETWEEN 256 AND 30000000),
  source_digest char(64) NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
  version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
  imported_at timestamptz(3) NOT NULL DEFAULT now(),
  created_at timestamptz(3) NOT NULL DEFAULT now()
);
-- At most one world is shown to players.
CREATE UNIQUE INDEX world_catalog_single_active ON world_catalog((true)) WHERE active;

CREATE TABLE world_regions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  world_id varchar(64) NOT NULL REFERENCES world_catalog(world_id) ON DELETE RESTRICT,
  code varchar(64) NOT NULL CHECK (code ~ '^[a-z0-9][a-z0-9._-]{1,63}$'),
  display_name varchar(80) NOT NULL CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 80),
  kind varchar(16) NOT NULL CHECK (kind IN ('CORE','STATE','BORDERLAND','RIM','FRONTIER_SLOT','FRONTIER_WILD','SEA')),
  clan_id uuid REFERENCES clans(id) ON DELETE RESTRICT,
  status varchar(10) NOT NULL CHECK (status IN ('RESERVED','ACTIVE','ARCHIVED')),
  natural_profile jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(natural_profile) = 'object'),
  resource_profile jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(resource_profile) = 'object'),
  capital_x integer, capital_y integer, capital_z integer,
  spawn_x integer, spawn_y integer, spawn_z integer,
  pvp_policy varchar(10) NOT NULL CHECK (pvp_policy IN ('OFF','WAR_ONLY','ON')),
  build_policy varchar(12) NOT NULL CHECK (build_policy IN ('MEMBERS','OPEN','NONE','OWNER_ZONE')),
  color_override char(6) CHECK (color_override IS NULL OR color_override ~ '^[0-9A-F]{6}$'),
  version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at timestamptz(3) NOT NULL DEFAULT now(),
  updated_at timestamptz(3) NOT NULL DEFAULT now(),
  UNIQUE (world_id, code),
  -- Only state regions and activated frontier slots belong to a clan.
  CHECK (clan_id IS NULL OR kind IN ('STATE','FRONTIER_SLOT')),
  CHECK ((capital_x IS NULL) = (capital_z IS NULL) AND (spawn_x IS NULL) = (spawn_z IS NULL))
);
CREATE INDEX world_regions_clan ON world_regions(clan_id) WHERE clan_id IS NOT NULL;

CREATE TABLE region_borders (
  region_id uuid PRIMARY KEY REFERENCES world_regions(id) ON DELETE CASCADE,
  polygon jsonb NOT NULL CHECK (jsonb_typeof(polygon) = 'array' AND jsonb_array_length(polygon) BETWEEN 3 AND 4096),
  min_x integer NOT NULL, min_z integer NOT NULL, max_x integer NOT NULL, max_z integer NOT NULL,
  source varchar(10) NOT NULL CHECK (source IN ('BLUEPRINT','ADMIN')),
  valid_from timestamptz(3) NOT NULL DEFAULT now(),
  CHECK (min_x < max_x AND min_z < max_z)
);

CREATE TABLE strategic_points (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  world_id varchar(64) NOT NULL REFERENCES world_catalog(world_id) ON DELETE RESTRICT,
  code varchar(64) NOT NULL CHECK (code ~ '^[a-z0-9][a-z0-9._-]{1,63}$'),
  type varchar(16) NOT NULL CHECK (type IN ('CAPITAL','CLAN_CAPITAL','FORT','MINE','VILLAGE','PORT','BRIDGE','PASS',
    'WATCHTOWER','RUINS','RESOURCE_SITE','OUTPOST','EVENT_ZONE','BOSS_LAIR')),
  name varchar(80) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 80),
  region_id uuid REFERENCES world_regions(id) ON DELETE RESTRICT,
  x integer NOT NULL, y integer, z integer NOT NULL,
  radius integer NOT NULL DEFAULT 16 CHECK (radius BETWEEN 4 AND 256),
  capturable boolean NOT NULL DEFAULT false,
  owner_clan_id uuid REFERENCES clans(id) ON DELETE RESTRICT,
  capture_state varchar(10) NOT NULL DEFAULT 'NEUTRAL'
    CHECK (capture_state IN ('NEUTRAL','CONTESTED','CAPTURING','CONTROLLED','PROTECTED','AVAILABLE')),
  capture_start timestamptz(3), capture_end timestamptz(3),
  protection_until timestamptz(3), lease_until timestamptz(3),
  resource_profile jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(resource_profile) = 'object'),
  map_icon varchar(32) NOT NULL CHECK (map_icon ~ '^[a-z0-9_-]{1,32}$'),
  enabled boolean NOT NULL DEFAULT true,
  config_version bigint NOT NULL DEFAULT 1 CHECK (config_version >= 1),
  created_at timestamptz(3) NOT NULL DEFAULT now(),
  updated_at timestamptz(3) NOT NULL DEFAULT now(),
  UNIQUE (world_id, code),
  CHECK (capturable OR (owner_clan_id IS NULL AND capture_state = 'NEUTRAL')),
  -- Held states need an owner; NEUTRAL has none; a running capture may or may not have a defender.
  CHECK (capture_state NOT IN ('CONTROLLED','PROTECTED','AVAILABLE') OR owner_clan_id IS NOT NULL),
  CHECK (capture_state <> 'NEUTRAL' OR owner_clan_id IS NULL)
);
CREATE INDEX strategic_points_owner ON strategic_points(owner_clan_id) WHERE owner_clan_id IS NOT NULL;

CREATE TABLE resource_sites (
  point_id uuid PRIMARY KEY REFERENCES strategic_points(id) ON DELETE RESTRICT,
  resource_type varchar(12) NOT NULL CHECK (resource_type IN ('FERROUS','NONFERROUS','PRECIOUS','ARCANE','TECH',
    'TIMBER','AGRARIAN','ALCHEMY','MARINE')),
  resource_tier smallint NOT NULL CHECK (resource_tier BETWEEN 1 AND 4),
  kind varchar(5) NOT NULL CHECK (kind IN ('PIT','CACHE')),
  capture_cooldown_seconds integer NOT NULL DEFAULT 7200 CHECK (capture_cooldown_seconds BETWEEN 0 AND 604800),
  respawn_interval_seconds integer NOT NULL CHECK (respawn_interval_seconds BETWEEN 300 AND 604800),
  yield jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(yield) = 'object'),
  next_refresh timestamptz(3), last_refresh timestamptz(3),
  pit_template varchar(128)
);

CREATE TABLE region_connections (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  world_id varchar(64) NOT NULL REFERENCES world_catalog(world_id) ON DELETE RESTRICT,
  code varchar(64) NOT NULL CHECK (code ~ '^[a-z0-9][a-z0-9._-]{1,63}$'),
  from_region uuid NOT NULL REFERENCES world_regions(id) ON DELETE CASCADE,
  to_region uuid NOT NULL REFERENCES world_regions(id) ON DELETE CASCADE,
  kind varchar(8) NOT NULL CHECK (kind IN ('ROAD','BRIDGE','PASS','PORT','RAIL')),
  polyline jsonb NOT NULL CHECK (jsonb_typeof(polyline) = 'array' AND jsonb_array_length(polyline) BETWEEN 2 AND 4096),
  strategic_point_id uuid REFERENCES strategic_points(id) ON DELETE SET NULL,
  UNIQUE (world_id, code),
  CHECK (from_region <> to_region)
);

CREATE TABLE resource_site_ownership (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  point_id uuid NOT NULL REFERENCES strategic_points(id) ON DELETE RESTRICT,
  clan_id uuid NOT NULL REFERENCES clans(id) ON DELETE RESTRICT,
  acquired_at timestamptz(3) NOT NULL DEFAULT now(),
  lease_until timestamptz(3),
  released_at timestamptz(3),
  release_reason varchar(32),
  CHECK ((released_at IS NULL) = (release_reason IS NULL) AND (released_at IS NULL OR released_at >= acquired_at))
);
CREATE UNIQUE INDEX resource_site_ownership_current ON resource_site_ownership(point_id) WHERE released_at IS NULL;

CREATE TABLE capture_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  point_id uuid NOT NULL REFERENCES strategic_points(id) ON DELETE RESTRICT,
  declared_by_clan uuid NOT NULL REFERENCES clans(id) ON DELETE RESTRICT,
  declared_by_user uuid REFERENCES users(id) ON DELETE RESTRICT,
  state varchar(10) NOT NULL CHECK (state IN ('PREPARING','ACTIVE','OVERTIME','FINISHED','VOIDED','CANCELLED')),
  prep_start timestamptz(3) NOT NULL, active_start timestamptz(3) NOT NULL, active_end timestamptz(3) NOT NULL,
  winner_clan uuid REFERENCES clans(id) ON DELETE RESTRICT,
  outcome varchar(10) CHECK (outcome IS NULL OR outcome IN ('CAPTURED','DEFENDED','NO_WINNER','VOIDED','CANCELLED')),
  snapshot jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(snapshot) = 'object' AND octet_length(snapshot::text) <= 16384),
  version bigint NOT NULL DEFAULT 1,
  created_at timestamptz(3) NOT NULL DEFAULT now(),
  CHECK (prep_start <= active_start AND active_start < active_end)
);
-- One running capture per point.
CREATE UNIQUE INDEX capture_events_running ON capture_events(point_id) WHERE state IN ('PREPARING','ACTIVE','OVERTIME');

CREATE TABLE capture_event_scores (
  event_id uuid NOT NULL REFERENCES capture_events(id) ON DELETE CASCADE,
  clan_id uuid NOT NULL REFERENCES clans(id) ON DELETE RESTRICT,
  progress numeric(6,3) NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  kills integer NOT NULL DEFAULT 0 CHECK (kills >= 0),
  deaths integer NOT NULL DEFAULT 0 CHECK (deaths >= 0),
  presence_seconds integer NOT NULL DEFAULT 0 CHECK (presence_seconds >= 0),
  peak_players integer NOT NULL DEFAULT 0 CHECK (peak_players >= 0),
  PRIMARY KEY (event_id, clan_id)
);

CREATE TABLE resource_site_history (
  id bigserial PRIMARY KEY,
  point_id uuid NOT NULL REFERENCES strategic_points(id) ON DELETE RESTRICT,
  at timestamptz(3) NOT NULL DEFAULT now(),
  event varchar(16) NOT NULL CHECK (event IN ('IMPORTED','DECLARED','ACTIVE','CAPTURED','DEFENDED','VOIDED','LEASE_EXPIRED',
    'NEUTRALIZED','REFRESHED','ADMIN_TRANSFER','ADMIN_RESET','ADMIN_CANCEL','DISABLED','ENABLED')),
  clan_id uuid REFERENCES clans(id) ON DELETE RESTRICT,
  capture_event_id uuid REFERENCES capture_events(id) ON DELETE RESTRICT,
  actor_user_id uuid REFERENCES users(id) ON DELETE RESTRICT,
  actor_kind varchar(10) NOT NULL CHECK (actor_kind IN ('USER','SERVICE','OPERATOR','SYSTEM')),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 16384)
);
CREATE INDEX resource_site_history_point ON resource_site_history(point_id, at);

CREATE TABLE map_markers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  world_id varchar(64) NOT NULL REFERENCES world_catalog(world_id) ON DELETE RESTRICT,
  code varchar(64) NOT NULL CHECK (code ~ '^[a-z0-9][a-z0-9._-]{1,63}$'),
  layer varchar(16) NOT NULL CHECK (layer IN ('capitals','points','sites','roads','events','labels')),
  kind varchar(16) NOT NULL CHECK (kind ~ '^[A-Z_]{2,16}$'),
  ref_id uuid,
  x integer NOT NULL, z integer NOT NULL,
  label varchar(80) NOT NULL CHECK (char_length(btrim(label)) BETWEEN 1 AND 80),
  icon varchar(32) NOT NULL CHECK (icon ~ '^[a-z0-9_-]{1,32}$'),
  visibility varchar(6) NOT NULL DEFAULT 'PUBLIC' CHECK (visibility IN ('PUBLIC','CLAN','OWNER')),
  visible_from timestamptz(3), visible_until timestamptz(3),
  UNIQUE (world_id, code)
);

GRANT SELECT,INSERT,UPDATE ON world_catalog,world_regions,region_borders,region_connections,strategic_points,resource_sites,
  resource_site_ownership,capture_events,capture_event_scores,map_markers TO anime_app;
GRANT DELETE ON region_borders,region_connections,map_markers,capture_event_scores TO anime_app;
-- History is append-only for the application role.
GRANT SELECT,INSERT ON resource_site_history TO anime_app;
GRANT USAGE ON SEQUENCE resource_site_history_id_seq TO anime_app;
