#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run as root'; exit 1; }
cd /srv/animeoncraft
umask 077
command -v flock >/dev/null
command -v tar >/dev/null
test -x /usr/bin/node
test -d .tools/backups
# Ensure the existing backup owner can still create start/stop snapshots.
test "$(stat -c %U .tools/backups)" = animeoncraft
chmod 700 .tools/backups
install -m 644 infra/friends/animeoncraft-backup.service /etc/systemd/system/
install -m 644 infra/friends/animeoncraft-backup.timer /etc/systemd/system/
mkdir -p /etc/systemd/system/animeoncraft-game.service.d
printf '[Service]\nUMask=0077\n' > /etc/systemd/system/animeoncraft-game.service.d/backup-privacy.conf
node scripts/scheduled-friends-backup.mjs --initialize
systemctl daemon-reload
systemctl enable --now animeoncraft-backup.timer
systemctl enable --now animeoncraft-certbot.timer
# Export an existing immutable stopped-server copy; do not stop the live game.
flock -n /run/lock/animeoncraft-backup.lock node scripts/scheduled-friends-backup.mjs --export-latest
node scripts/scheduled-friends-backup.mjs --check
systemctl list-timers animeoncraft-backup.timer animeoncraft-certbot.timer --no-pager
echo 'AOC_BACKUP_SCHEDULE_INSTALLED (no immediate game restart)'
