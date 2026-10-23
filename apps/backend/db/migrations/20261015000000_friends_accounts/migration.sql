BEGIN;
ALTER TABLE users ADD COLUMN login varchar(64) NOT NULL
  DEFAULT ('legacy_' || replace(gen_random_uuid()::text, '-', ''));
-- Preserve every account ID, password hash, contact address, role and membership.
-- Unique ordinary email prefixes become friendly handles; collisions get UUID handles.
WITH names AS (
  SELECT id, lower(split_part(email_key, '@', 1)) AS candidate FROM users
), counted AS (
  SELECT id, candidate, count(*) OVER (PARTITION BY candidate) AS occurrences FROM names
)
UPDATE users u SET login = CASE
  WHEN c.occurrences = 1 AND c.candidate ~ '^[a-z0-9][a-z0-9_.-]{2,63}$'
    AND c.candidate NOT LIKE 'legacy\_%' ESCAPE '\' THEN c.candidate
  ELSE 'legacy_' || replace(u.id::text, '-', '') END
FROM counted c WHERE c.id = u.id;
ALTER TABLE users ADD COLUMN login_key varchar(64) GENERATED ALWAYS AS (lower(login)) STORED;
ALTER TABLE users ADD COLUMN password_change_required boolean NOT NULL DEFAULT false;
ALTER TABLE users ADD CONSTRAINT users_login_format CHECK (login ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$');
CREATE UNIQUE INDEX users_login_key_key ON users(login_key);
COMMIT;
