-- Production default privileges may grant UPDATE/DELETE to anime_app on new tables.
-- Revoke table-wide permissions before granting the review-only interface explicitly.
-- Existing suspicion reports are preserved.
REVOKE ALL PRIVILEGES ON TABLE anticheat_reports FROM PUBLIC;
REVOKE ALL PRIVILEGES ON TABLE anticheat_reports FROM anime_app;
GRANT SELECT, INSERT ON TABLE anticheat_reports TO anime_app;
GRANT UPDATE (reviewed_at, reviewed_by) ON TABLE anticheat_reports TO anime_app;
