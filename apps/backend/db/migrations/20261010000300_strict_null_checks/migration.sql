-- SQL CHECK treats NULL as success: require terminal timestamps/reasons explicitly.
ALTER TABLE minecraft_accounts DROP CONSTRAINT minecraft_accounts_check;
ALTER TABLE minecraft_accounts ADD CONSTRAINT minecraft_accounts_state_dates CHECK (
  (state = 'VERIFIED' AND revoked_at IS NULL) OR (state = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_at >= verified_at));
ALTER TABLE clan_members DROP CONSTRAINT clan_members_check;
ALTER TABLE clan_members ADD CONSTRAINT clan_members_end_state CHECK (
  (ended_at IS NULL AND end_reason IS NULL) OR (ended_at IS NOT NULL AND end_reason IS NOT NULL AND ended_at >= joined_at AND char_length(btrim(end_reason)) BETWEEN 1 AND 1000));
ALTER TABLE clan_applications DROP CONSTRAINT clan_applications_check2;
ALTER TABLE clan_applications ADD CONSTRAINT clan_applications_reason CHECK (
  status NOT IN ('REJECTED','CANCELLED') OR (reason IS NOT NULL AND char_length(btrim(reason)) BETWEEN 1 AND 1000));
ALTER TABLE launcher_versions ADD CONSTRAINT launcher_target_nonempty CHECK (char_length(btrim(artifact_target)) > 0);
