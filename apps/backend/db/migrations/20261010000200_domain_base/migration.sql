-- Additive upgrade: preserve existing users, disabled seeds and their identifiers.
ALTER TABLE users ADD CONSTRAINT users_email_canonical CHECK (email_key = lower(normalize(btrim(email), NFKC)));
ALTER TABLE clans ADD CONSTRAINT clans_archive_disabled CHECK (archived_at IS NULL OR enabled = false);

CREATE TYPE minecraft_account_state AS ENUM ('VERIFIED', 'REVOKED');
CREATE TYPE session_client_kind AS ENUM ('LAUNCHER', 'WEB_ADMIN');
CREATE TYPE application_status AS ENUM ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'CANCELLED');
CREATE TYPE whitelist_kind AS ENUM ('MEMBERSHIP', 'OWNER');
CREATE TYPE ban_scope AS ENUM ('GAME', 'ACCOUNT');
CREATE TYPE audit_actor_kind AS ENUM ('USER', 'SERVICE', 'OPERATOR', 'DEVELOPMENT');

CREATE TABLE media_assets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), object_key TEXT NOT NULL UNIQUE,
  format VARCHAR(8) NOT NULL CHECK (format IN ('PNG','JPEG','WEBP')),
  sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'),
  size BIGINT NOT NULL CHECK (size > 0), width INTEGER NOT NULL CHECK (width > 0),
  height INTEGER NOT NULL CHECK (height > 0), status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','READY','REJECTED')),
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE clans ADD COLUMN logo_media_id UUID REFERENCES media_assets(id) ON DELETE RESTRICT;

CREATE TABLE minecraft_accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  minecraft_uuid UUID NOT NULL UNIQUE, nickname VARCHAR(16) NOT NULL CHECK (nickname ~ '^[A-Za-z0-9_]{3,16}$'),
  state minecraft_account_state NOT NULL, verified_at TIMESTAMPTZ(3) NOT NULL,
  revoked_at TIMESTAMPTZ(3), version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK ((state = 'VERIFIED' AND revoked_at IS NULL) OR (state = 'REVOKED' AND revoked_at >= verified_at))
);
CREATE UNIQUE INDEX minecraft_accounts_one_verified ON minecraft_accounts(user_id) WHERE state = 'VERIFIED';

CREATE TABLE sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  client_kind session_client_kind NOT NULL, created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  absolute_expires_at TIMESTAMPTZ(3) NOT NULL, idle_expires_at TIMESTAMPTZ(3) NOT NULL,
  revoked_at TIMESTAMPTZ(3), auth_epoch BIGINT NOT NULL CHECK (auth_epoch >= 0),
  mfa_verified_at TIMESTAMPTZ(3), last_step_up_at TIMESTAMPTZ(3),
  CHECK (absolute_expires_at > created_at AND idle_expires_at > created_at AND idle_expires_at <= absolute_expires_at),
  CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX sessions_user_revoked ON sessions(user_id, revoked_at);

CREATE TABLE clan_roles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), clan_id UUID NOT NULL REFERENCES clans(id) ON DELETE RESTRICT,
  code VARCHAR(16) NOT NULL CHECK (code = 'MANAGER'), label VARCHAR(64) NOT NULL CHECK (char_length(label) BETWEEN 2 AND 64),
  can_remove_members BOOLEAN NOT NULL DEFAULT false, version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE(clan_id, code), UNIQUE(id, clan_id)
);
CREATE TABLE clan_applications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  clan_id UUID NOT NULL REFERENCES clans(id) ON DELETE RESTRICT,
  message VARCHAR(4000) NOT NULL CHECK (char_length(message) BETWEEN 50 AND 4000),
  status application_status NOT NULL DEFAULT 'PENDING', decided_by UUID REFERENCES users(id) ON DELETE RESTRICT,
  decided_at TIMESTAMPTZ(3), reason VARCHAR(1000), version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(id,user_id,clan_id),
  CHECK ((status = 'PENDING' AND decided_at IS NULL AND decided_by IS NULL AND reason IS NULL)
    OR (status <> 'PENDING' AND decided_at IS NOT NULL AND decided_at >= created_at)),
  CHECK (status NOT IN ('ACCEPTED','REJECTED') OR (decided_by IS NOT NULL AND decided_by <> user_id)),
  CHECK (status NOT IN ('REJECTED','CANCELLED') OR char_length(btrim(reason)) BETWEEN 1 AND 1000)
);
CREATE UNIQUE INDEX clan_applications_one_pending ON clan_applications(user_id) WHERE status = 'PENDING';
CREATE INDEX clan_applications_queue ON clan_applications(clan_id,status,created_at,id);

CREATE TABLE clan_members (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), clan_id UUID NOT NULL REFERENCES clans(id) ON DELETE RESTRICT,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  joined_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, ended_at TIMESTAMPTZ(3), end_reason VARCHAR(1000),
  accepted_application_id UUID UNIQUE, version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE(id,user_id),
  FOREIGN KEY(accepted_application_id,user_id,clan_id) REFERENCES clan_applications(id,user_id,clan_id) ON DELETE RESTRICT,
  CHECK ((ended_at IS NULL AND end_reason IS NULL) OR (ended_at >= joined_at AND char_length(btrim(end_reason)) BETWEEN 1 AND 1000))
);
CREATE UNIQUE INDEX clan_members_one_active ON clan_members(user_id) WHERE ended_at IS NULL;
CREATE INDEX clan_members_roster ON clan_members(clan_id,ended_at,joined_at,id);

CREATE TABLE clan_manager_assignments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  clan_id UUID NOT NULL REFERENCES clans(id) ON DELETE RESTRICT, role_id UUID NOT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, ended_at TIMESTAMPTZ(3),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  FOREIGN KEY(role_id,clan_id) REFERENCES clan_roles(id,clan_id) ON DELETE RESTRICT,
  CHECK (ended_at IS NULL OR ended_at >= created_at)
);
CREATE UNIQUE INDEX clan_manager_assignments_one_active ON clan_manager_assignments(user_id,clan_id) WHERE ended_at IS NULL;

CREATE TABLE whitelist (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  kind whitelist_kind NOT NULL, membership_id UUID, granted_by UUID REFERENCES users(id) ON DELETE RESTRICT,
  reason VARCHAR(1000) NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 1000),
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, expires_at TIMESTAMPTZ(3), revoked_at TIMESTAMPTZ(3),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  FOREIGN KEY(membership_id,user_id) REFERENCES clan_members(id,user_id) ON DELETE RESTRICT,
  CHECK ((kind = 'MEMBERSHIP') = (membership_id IS NOT NULL)),
  CHECK (expires_at IS NULL OR expires_at > created_at), CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE UNIQUE INDEX whitelist_one_membership ON whitelist(membership_id) WHERE revoked_at IS NULL AND membership_id IS NOT NULL;
CREATE UNIQUE INDEX whitelist_one_owner ON whitelist(user_id) WHERE revoked_at IS NULL AND kind = 'OWNER';

CREATE TABLE bans (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  scope ban_scope NOT NULL, reason_private VARCHAR(1000) NOT NULL CHECK (char_length(btrim(reason_private)) BETWEEN 1 AND 1000),
  message_public VARCHAR(1000) NOT NULL CHECK (char_length(btrim(message_public)) BETWEEN 1 AND 1000),
  issued_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, expires_at TIMESTAMPTZ(3), revoked_at TIMESTAMPTZ(3),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (expires_at IS NULL OR expires_at > created_at), CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX bans_policy ON bans(user_id,revoked_at,expires_at);

CREATE TABLE news (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), slug VARCHAR(100) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  title VARCHAR(160) NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 160),
  body_markdown VARCHAR(20000) NOT NULL CHECK (char_length(btrim(body_markdown)) BETWEEN 1 AND 20000 AND body_markdown !~* '<[/]?[a-z][^>]*>'),
  published_at TIMESTAMPTZ(3), archived_at TIMESTAMPTZ(3), author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (published_at IS NULL OR published_at >= created_at), CHECK (archived_at IS NULL OR archived_at >= created_at)
);
CREATE INDEX news_public ON news(published_at,id) WHERE published_at IS NOT NULL AND archived_at IS NULL;

CREATE TABLE launcher_versions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), channel VARCHAR(32) NOT NULL CHECK (channel ~ '^[a-z0-9-]{1,32}$'),
  platform VARCHAR(32) NOT NULL CHECK (platform IN ('windows-x86_64','linux-x86_64','macos-aarch64','macos-x86_64')),
  version_label VARCHAR(64) NOT NULL CHECK (char_length(btrim(version_label)) > 0),
  release_sequence BIGINT NOT NULL CHECK (release_sequence >= 0), min_api_major INTEGER NOT NULL CHECK (min_api_major > 0),
  artifact_target VARCHAR(256) NOT NULL CHECK (artifact_target !~ '(^/|(^|/)\.\.(/|$)|[\\])'),
  sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'), size BIGINT NOT NULL CHECK (size > 0),
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, published_at TIMESTAMPTZ(3), revoked_at TIMESTAMPTZ(3),
  UNIQUE(channel,platform,release_sequence), CHECK (published_at IS NULL OR published_at >= created_at),
  CHECK (revoked_at IS NULL OR (published_at IS NOT NULL AND revoked_at >= published_at))
);
CREATE FUNCTION immutable_published_launcher() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.published_at IS NOT NULL AND (TG_OP = 'DELETE' OR (to_jsonb(NEW) - 'revoked_at') <> (to_jsonb(OLD) - 'revoked_at')) THEN
    RAISE EXCEPTION 'Published launcher metadata is immutable' USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER launcher_versions_immutable BEFORE UPDATE OR DELETE ON launcher_versions FOR EACH ROW EXECUTE FUNCTION immutable_published_launcher();

CREATE TABLE game_servers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), name VARCHAR(64) NOT NULL,
  maintenance BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE server_boots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), server_id UUID NOT NULL REFERENCES game_servers(id) ON DELETE RESTRICT,
  started_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, ended_at TIMESTAMPTZ(3), protocol_major INTEGER NOT NULL CHECK (protocol_major > 0),
  UNIQUE(id,server_id), CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE UNIQUE INDEX server_boots_one_current ON server_boots(server_id) WHERE ended_at IS NULL;
CREATE TABLE server_events (
  event_id UUID NOT NULL, server_id UUID NOT NULL REFERENCES game_servers(id) ON DELETE RESTRICT, boot_id UUID NOT NULL,
  kind VARCHAR(32) NOT NULL CHECK (kind IN ('PLAYER_JOINED','PLAYER_LEFT','HEARTBEAT','STATISTICS')),
  observed_at TIMESTAMPTZ(3) NOT NULL, received_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  schema_version INTEGER NOT NULL CHECK (schema_version > 0), payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 65536),
  processing_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (processing_status IN ('PENDING','PROCESSED','DEAD_LETTER')),
  PRIMARY KEY(server_id,boot_id,event_id), FOREIGN KEY(boot_id,server_id) REFERENCES server_boots(id,server_id) ON DELETE RESTRICT
);
CREATE TABLE player_statistics (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT, server_id UUID NOT NULL REFERENCES game_servers(id) ON DELETE RESTRICT,
  playtime_seconds BIGINT NOT NULL DEFAULT 0 CHECK (playtime_seconds >= 0), last_seen_at TIMESTAMPTZ(3),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0), updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id,server_id)
);

CREATE TABLE audit_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), actor_user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
  actor_service_id UUID, actor_kind audit_actor_kind NOT NULL, action VARCHAR(64) NOT NULL,
  target_type VARCHAR(64) NOT NULL, target_id UUID NOT NULL, before_redacted JSONB, after_redacted JSONB,
  reason VARCHAR(1000) NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 1000), request_id UUID NOT NULL,
  time TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK ((actor_kind = 'USER' AND actor_user_id IS NOT NULL AND actor_service_id IS NULL)
    OR (actor_kind = 'SERVICE' AND actor_service_id IS NOT NULL AND actor_user_id IS NULL)
    OR (actor_kind IN ('OPERATOR','DEVELOPMENT') AND actor_user_id IS NULL AND actor_service_id IS NULL)),
  CHECK (before_redacted IS NULL OR (jsonb_typeof(before_redacted) = 'object' AND octet_length(before_redacted::text) <= 16384)),
  CHECK (after_redacted IS NULL OR (jsonb_typeof(after_redacted) = 'object' AND octet_length(after_redacted::text) <= 16384))
);
CREATE INDEX audit_logs_actor_time ON audit_logs(actor_user_id,time);
CREATE INDEX audit_logs_target_time ON audit_logs(target_type,target_id,time);
CREATE TABLE application_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), application_id UUID NOT NULL REFERENCES clan_applications(id) ON DELETE RESTRICT,
  status application_status NOT NULL, actor_user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
  reason VARCHAR(1000), time TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

GRANT SELECT, INSERT, UPDATE ON TABLE minecraft_accounts,sessions,clan_roles,clan_applications,clan_members,clan_manager_assignments,whitelist,bans,news,launcher_versions,media_assets,game_servers,server_boots,server_events,player_statistics TO anime_app;
GRANT SELECT, INSERT ON TABLE audit_logs,application_history TO anime_app;
REVOKE DELETE ON TABLE users,clans FROM anime_app;
