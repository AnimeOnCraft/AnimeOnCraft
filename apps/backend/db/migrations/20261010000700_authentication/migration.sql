CREATE TYPE auth_token_kind AS ENUM ('ACCESS','REFRESH');
CREATE TYPE auth_challenge_kind AS ENUM ('EMAIL_VERIFY','PASSWORD_RESET','LOGIN_MFA','MINECRAFT_BIND');
CREATE TABLE auth_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE RESTRICT,
  token_hash CHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[a-f0-9]{64}$'), kind auth_token_kind NOT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, expires_at TIMESTAMPTZ(3) NOT NULL,
  used_at TIMESTAMPTZ(3), revoked_at TIMESTAMPTZ(3), CHECK (expires_at > created_at),
  CHECK (used_at IS NULL OR (kind = 'REFRESH' AND used_at >= created_at))
);
CREATE INDEX auth_tokens_session ON auth_tokens(session_id,revoked_at);
CREATE UNIQUE INDEX auth_tokens_one_active_refresh ON auth_tokens(session_id) WHERE kind='REFRESH' AND used_at IS NULL AND revoked_at IS NULL;
CREATE TABLE auth_challenges (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  session_id UUID REFERENCES sessions(id) ON DELETE RESTRICT, kind auth_challenge_kind NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[a-f0-9]{64}$'), auth_epoch BIGINT NOT NULL CHECK(auth_epoch >= 0),
  client_kind session_client_kind, created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMPTZ(3) NOT NULL, consumed_at TIMESTAMPTZ(3), CHECK (expires_at > created_at),
  CHECK (consumed_at IS NULL OR consumed_at >= created_at),
  CHECK ((kind = 'LOGIN_MFA' AND client_kind IS NOT NULL AND session_id IS NULL)
    OR (kind = 'MINECRAFT_BIND' AND session_id IS NOT NULL AND client_kind IS NULL)
    OR (kind IN ('EMAIL_VERIFY','PASSWORD_RESET') AND session_id IS NULL AND client_kind IS NULL))
);
CREATE INDEX auth_challenges_user ON auth_challenges(user_id,kind,consumed_at);
CREATE TABLE mfa_credentials (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE RESTRICT, secret_ciphertext TEXT NOT NULL,
  key_id VARCHAR(64) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT false, pending_expires_at TIMESTAMPTZ(3),
  last_used_step BIGINT NOT NULL DEFAULT -1 CHECK (last_used_step >= -1),
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK ((enabled AND pending_expires_at IS NULL) OR (NOT enabled AND pending_expires_at IS NOT NULL)),
  CHECK (octet_length(secret_ciphertext) BETWEEN 48 AND 1024)
);
CREATE TABLE mfa_recovery_codes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  code_hash CHAR(64) NOT NULL UNIQUE CHECK (code_hash ~ '^[a-f0-9]{64}$'),
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, used_at TIMESTAMPTZ(3), CHECK(used_at IS NULL OR used_at >= created_at)
);
CREATE INDEX mfa_recovery_codes_user ON mfa_recovery_codes(user_id,used_at);
GRANT SELECT,INSERT,UPDATE ON auth_tokens,auth_challenges,mfa_credentials,mfa_recovery_codes TO anime_app;
-- Expired/consumed token history is retained by the app. Operator maintenance is a separate task.
