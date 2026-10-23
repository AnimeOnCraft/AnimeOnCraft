-- A creator can join directly already; allow the equivalent self-application decision.
-- Preserve the self-review prohibition for every ordinary player/manager at DB level.
ALTER TABLE clan_applications DROP CONSTRAINT clan_applications_check1;
ALTER TABLE clan_applications ADD CONSTRAINT clan_applications_decider_required
  CHECK (status NOT IN ('ACCEPTED','REJECTED') OR decided_by IS NOT NULL);

CREATE FUNCTION guard_clan_application_self_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IN ('ACCEPTED','REJECTED') AND NEW.decided_by = NEW.user_id
     AND NOT EXISTS (SELECT 1 FROM users WHERE id=NEW.user_id AND system_role='OWNER' AND state='ACTIVE') THEN
    RAISE EXCEPTION 'Only the active creator may decide their own clan application'
      USING ERRCODE='23514', CONSTRAINT='clan_applications_self_review_owner';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER clan_application_self_review_owner
  BEFORE INSERT OR UPDATE OF status,decided_by,user_id ON clan_applications
  FOR EACH ROW EXECUTE FUNCTION guard_clan_application_self_review();
