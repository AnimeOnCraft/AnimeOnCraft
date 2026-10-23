-- Reserve a scoped service identity, without implementing credentials or admission.
CREATE TABLE service_principals (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), server_id UUID NOT NULL REFERENCES game_servers(id) ON DELETE RESTRICT,
  name VARCHAR(64) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 64), enabled BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_service_fkey FOREIGN KEY(actor_service_id) REFERENCES service_principals(id) ON DELETE RESTRICT;
GRANT SELECT, INSERT, UPDATE ON TABLE service_principals TO anime_app;
