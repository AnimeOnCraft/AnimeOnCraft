ALTER TABLE beta_campaign DROP CONSTRAINT beta_campaign_check;
ALTER TABLE beta_campaign ADD CONSTRAINT beta_campaign_window_check CHECK (
  (starts_at IS NULL AND ends_at IS NULL)
  OR (starts_at IS NOT NULL AND (ends_at IS NULL OR ends_at > starts_at))
);
