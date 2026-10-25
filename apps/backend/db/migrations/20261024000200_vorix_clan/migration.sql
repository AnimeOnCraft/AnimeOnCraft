-- Sixth official clan VØRIX (docs/world-design/added-clans.json). The code stays ASCII (clans_code_format); the name is
-- shown in the launcher. Enabled at once, so it appears in the clan list; its land comes with the world catalog patch.
INSERT INTO clans (name, short_code, code_key, enabled, color, description)
VALUES ('VØRIX', 'VORIX', 'VORIX', true, '36D399', '')
ON CONFLICT (code_key) DO NOTHING;
