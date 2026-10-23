ALTER TABLE game_tickets ADD COLUMN launcher_version TEXT;
ALTER TABLE game_tickets ADD CONSTRAINT game_tickets_launcher_version CHECK (
  launcher_version IS NULL OR launcher_version ~ '^(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})$'
);
