-- Keep permanent Beta ownership while allowing reviewed personal catalog IDs.
ALTER TABLE player_capes DROP CONSTRAINT player_capes_cape_id_check;
ALTER TABLE player_capes ADD CONSTRAINT player_capes_cape_id_format
  CHECK (cape_id ~ '^[a-z][a-z0-9-]{0,31}$');
