-- Stable bounded administration pagination; no credentials or privilege grants are changed.
CREATE INDEX users_admin_page ON users(created_at,id);
CREATE INDEX news_admin_page ON news(created_at,id);
CREATE INDEX audit_logs_admin_page ON audit_logs(time,id);
CREATE INDEX bans_admin_user_page ON bans(user_id,created_at,id);
CREATE INDEX whitelist_admin_user_page ON whitelist(user_id,created_at,id);
