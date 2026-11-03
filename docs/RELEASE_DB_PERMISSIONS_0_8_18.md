# 0.8.18 database permissions follow-up

Production default privileges granted table-wide UPDATE and DELETE on newly created tables. Additive migration 20261103000300_anticheat_report_permissions explicitly revokes these permissions for anticheat_reports and grants only SELECT, INSERT and UPDATE of the review columns. Existing reports and game data are preserved. The disposable SQL/HTTP acceptance fixture reproduces the production defaults and passes with all 43 migrations.

The signed installer and game JAR are unchanged from v0.8.18. This follow-up tag contains the final database migration applied during deployment.
