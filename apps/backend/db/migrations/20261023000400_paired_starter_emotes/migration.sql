-- Add two cosmetic starter grants; preserve all previous ownership and progress.
INSERT INTO player_emote_grants(user_id,code,source)
SELECT id,code,'starter' FROM users CROSS JOIN
 (VALUES ('HIGH_FIVE'),('HANDSHAKE')) AS starter(code)
ON CONFLICT DO NOTHING;
