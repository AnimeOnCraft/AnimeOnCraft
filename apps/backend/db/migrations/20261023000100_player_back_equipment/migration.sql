-- One shared back slot. Existing cape ownership and legacy launcher response formats are preserved.
CREATE TABLE player_back_equipment (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    kind varchar(8) NOT NULL DEFAULT 'NONE',
    code varchar(32) NOT NULL DEFAULT '',
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at timestamptz(3) NOT NULL DEFAULT now(),
    CONSTRAINT player_back_equipment_valid CHECK (
        (kind = 'NONE' AND code = '') OR
        (kind = 'CAPE' AND code ~ '^[a-z][a-z0-9-]{0,31}$') OR
        (kind = 'BETA' AND code = 'RIFT_MANTLE')
    )
);
INSERT INTO player_back_equipment (user_id,kind,code)
SELECT user_id,'CAPE',cape_id FROM player_capes WHERE equipped;
GRANT SELECT,INSERT,UPDATE ON player_back_equipment TO anime_app;
