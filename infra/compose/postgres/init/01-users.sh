#!/usr/bin/env bash
set -Eeuo pipefail

if [[ -z "${AOC_DEV_APP_DB_PASSWORD:-}" ]]; then
  echo "AOC_DEV_APP_DB_PASSWORD is required" >&2
  exit 1
fi

psql --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set app_password="$AOC_DEV_APP_DB_PASSWORD" <<'EOSQL'
DO $block$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'anime_app') THEN
    CREATE ROLE anime_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
END
$block$;
ALTER ROLE anime_app PASSWORD :'app_password';
GRANT CONNECT ON DATABASE animeoncraft TO anime_app;
GRANT USAGE ON SCHEMA public TO anime_app;
ALTER DEFAULT PRIVILEGES FOR ROLE anime_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO anime_app;
EOSQL
