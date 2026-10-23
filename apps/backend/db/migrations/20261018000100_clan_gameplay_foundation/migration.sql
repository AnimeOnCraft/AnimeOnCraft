-- Foundation for server-authoritative clan progression, events, war windows and live presence.
-- Gameplay writers will be added only through the integration service; clients never write these tables.
CREATE TABLE clan_progression (
  clan_id uuid PRIMARY KEY REFERENCES clans(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  xp bigint NOT NULL DEFAULT 0 CHECK (xp >= 0),
  level integer NOT NULL DEFAULT 1 CHECK (level BETWEEN 1 AND 100),
  reputation integer NOT NULL DEFAULT 0 CHECK (reputation BETWEEN -1000000 AND 1000000),
  statistics jsonb NOT NULL DEFAULT '{}'::jsonb,
  version bigint NOT NULL DEFAULT 0,
  updated_at timestamptz(3) NOT NULL DEFAULT now()
);

CREATE TABLE clan_achievements (
  clan_id uuid NOT NULL REFERENCES clans(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  code varchar(64) NOT NULL,
  progress bigint NOT NULL DEFAULT 0 CHECK (progress >= 0),
  target bigint NOT NULL CHECK (target > 0),
  unlocked_at timestamptz(3),
  version bigint NOT NULL DEFAULT 0,
  updated_at timestamptz(3) NOT NULL DEFAULT now(),
  PRIMARY KEY (clan_id, code)
);

CREATE TABLE clan_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code varchar(64) NOT NULL,
  title varchar(160) NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('SCHEDULED','ACTIVE','FINISHED','CANCELLED')),
  starts_at timestamptz(3) NOT NULL,
  ends_at timestamptz(3) NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz(3) NOT NULL DEFAULT now(),
  CHECK (ends_at > starts_at)
);
CREATE UNIQUE INDEX clan_events_code ON clan_events(code);

CREATE TABLE clan_event_scores (
  event_id uuid NOT NULL REFERENCES clan_events(id) ON UPDATE NO ACTION ON DELETE CASCADE,
  clan_id uuid NOT NULL REFERENCES clans(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  score bigint NOT NULL DEFAULT 0 CHECK (score >= 0),
  reward_granted_at timestamptz(3),
  version bigint NOT NULL DEFAULT 0,
  updated_at timestamptz(3) NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, clan_id)
);

CREATE TABLE clan_war_windows (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  attacker_clan_id uuid NOT NULL REFERENCES clans(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  defender_clan_id uuid NOT NULL REFERENCES clans(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  status varchar(16) NOT NULL CHECK (status IN ('DECLARED','PREPARATION','ACTIVE','FINISHED','COOLDOWN','CANCELLED')),
  preparation_starts_at timestamptz(3) NOT NULL,
  active_starts_at timestamptz(3) NOT NULL,
  active_ends_at timestamptz(3) NOT NULL,
  cooldown_ends_at timestamptz(3),
  reason varchar(1000) NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz(3) NOT NULL DEFAULT now(),
  CHECK (attacker_clan_id <> defender_clan_id),
  CHECK (preparation_starts_at <= active_starts_at AND active_starts_at < active_ends_at),
  CHECK (cooldown_ends_at IS NULL OR cooldown_ends_at >= active_ends_at)
);
CREATE INDEX clan_war_windows_active ON clan_war_windows (active_starts_at, active_ends_at, status);

CREATE TABLE clan_online_presence (
  server_id uuid NOT NULL REFERENCES game_servers(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  user_id uuid NOT NULL REFERENCES users(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  clan_id uuid REFERENCES clans(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  online boolean NOT NULL DEFAULT false,
  last_seen_at timestamptz(3) NOT NULL DEFAULT now(),
  updated_at timestamptz(3) NOT NULL DEFAULT now(),
  PRIMARY KEY (server_id, user_id)
);
CREATE INDEX clan_online_presence_clan ON clan_online_presence (clan_id, online, last_seen_at);

CREATE TABLE clan_gameplay_events (
  event_id uuid PRIMARY KEY,
  server_id uuid NOT NULL REFERENCES game_servers(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  clan_id uuid NOT NULL REFERENCES clans(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  kind varchar(64) NOT NULL,
  xp_delta bigint NOT NULL DEFAULT 0 CHECK (xp_delta BETWEEN 0 AND 100000),
  reputation_delta integer NOT NULL DEFAULT 0 CHECK (reputation_delta BETWEEN -10000 AND 10000),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  occurred_at timestamptz(3) NOT NULL,
  created_at timestamptz(3) NOT NULL DEFAULT now()
);
CREATE INDEX clan_gameplay_events_clan_time ON clan_gameplay_events (clan_id, occurred_at DESC);

CREATE TABLE clan_territory_control (
  territory_code varchar(12) PRIMARY KEY,
  controller_clan_id uuid NOT NULL REFERENCES clans(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  captured_from_clan_id uuid NOT NULL REFERENCES clans(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  war_window_id uuid NOT NULL REFERENCES clan_war_windows(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
  captured_at timestamptz(3) NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  CHECK (controller_clan_id <> captured_from_clan_id)
);
