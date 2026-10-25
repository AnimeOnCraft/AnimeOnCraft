-- Clan roles beyond the single web-admin MANAGER: LEADER (one per clan, appointed by the owner), OFFICER and RECRUITER.
-- Assignments keep using clan_manager_assignments; the role row decides what the holder may do (community.service.ts).
-- MANAGER stays valid for assignments made earlier from the web admin. No data is changed.
ALTER TABLE clan_roles DROP CONSTRAINT clan_roles_code_check;
ALTER TABLE clan_roles ADD CONSTRAINT clan_roles_code_check CHECK (code IN ('MANAGER','LEADER','OFFICER','RECRUITER'));
