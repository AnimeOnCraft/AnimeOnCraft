CREATE TYPE "system_role" AS ENUM ('OWNER', 'PLAYER');
CREATE TYPE "user_state" AS ENUM ('ACTIVE', 'SUSPENDED');

CREATE TABLE "users" (
  "id" UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  "email" VARCHAR(320) NOT NULL,
  "email_key" VARCHAR(320) NOT NULL,
  "email_verified_at" TIMESTAMPTZ(3),
  "password_hash" TEXT NOT NULL,
  "system_role" "system_role" NOT NULL DEFAULT 'PLAYER',
  "state" "user_state" NOT NULL DEFAULT 'ACTIVE',
  "auth_epoch" BIGINT NOT NULL DEFAULT 0,
  "access_epoch" BIGINT NOT NULL DEFAULT 0,
  "version" BIGINT NOT NULL DEFAULT 0,
  "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updated_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "users_email_key_key" UNIQUE ("email_key"),
  CONSTRAINT "users_email_bounds" CHECK (char_length("email") BETWEEN 3 AND 320),
  CONSTRAINT "users_email_key_normalized" CHECK ("email_key" = lower(btrim("email_key"))),
  CONSTRAINT "users_epoch_nonnegative" CHECK ("auth_epoch" >= 0 AND "access_epoch" >= 0 AND "version" >= 0)
);

CREATE UNIQUE INDEX "users_single_owner_key" ON "users" (("system_role")) WHERE "system_role" = 'OWNER';

CREATE TABLE "clans" (
  "id" UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  "name" VARCHAR(64) NOT NULL,
  "short_code" VARCHAR(12) NOT NULL,
  "code_key" VARCHAR(12) NOT NULL,
  "description" VARCHAR(4000) NOT NULL DEFAULT '',
  "logo_url" TEXT,
  "discord_url" TEXT,
  "enabled" BOOLEAN NOT NULL DEFAULT false,
  "archived_at" TIMESTAMPTZ(3),
  "version" BIGINT NOT NULL DEFAULT 0,
  "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updated_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "clans_code_key_key" UNIQUE ("code_key"),
  CONSTRAINT "clans_name_bounds" CHECK (char_length("name") BETWEEN 2 AND 64),
  CONSTRAINT "clans_code_format" CHECK ("short_code" ~ '^[A-Z0-9]{2,12}$' AND "code_key" = upper("short_code")),
  CONSTRAINT "clans_version_nonnegative" CHECK ("version" >= 0)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE "users", "clans" TO anime_app;
