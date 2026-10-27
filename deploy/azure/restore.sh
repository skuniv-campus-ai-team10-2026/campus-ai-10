#!/usr/bin/env bash
# Restores a backup made by backup.sh (DB + photos). Destructive for the current data, so it requires --yes
# and first moves the current photo folder aside and takes one more backup of the current state.
#
# Usage: sudo bash /opt/skope/deploy/restore.sh /var/backups/skope/<timestamp> --yes
set -euo pipefail
SRC="${1:?usage: restore.sh <backup-dir> --yes}"
[[ "${2:-}" == "--yes" ]] || { echo "refusing without --yes (this replaces the current DB and photos)" >&2; exit 1; }
[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }
SRC="$(readlink -f "$SRC")"
( cd "$SRC" && sha256sum --quiet -c SHA256SUMS ) || { echo "checksum mismatch, nothing changed" >&2; exit 1; }
echo "== restoring $(basename "$SRC") (release $(cat "$SRC/RELEASE"))"
cat "$SRC/COUNTS"

echo "== safety backup of the current state"
/opt/skope/deploy/backup.sh

systemctl stop skope
mysql -e "DROP DATABASE IF EXISTS skope;"
gunzip -c "$SRC/skope.sql.gz" | mysql            # dump contains CREATE DATABASE / USE skope
mysql -e "GRANT ALL ON skope.* TO 'skope'@'localhost';"

TS="$(date +%Y%m%d-%H%M%S)"
mv /var/lib/skope/attachments "/var/lib/skope/attachments.before-restore-${TS}"
tar -C /var/lib/skope -xzf "$SRC/attachments.tar.gz"
chown -R skope:skope /var/lib/skope/attachments

systemctl start skope
for _ in $(seq 1 90); do curl -fsS --max-time 2 http://127.0.0.1:8080/api/meta >/dev/null 2>&1 && break; sleep 1; done
curl -fsS http://127.0.0.1:8080/api/meta; echo
mysql -N -e "SELECT 'reports',COUNT(*) FROM skope.reports UNION ALL SELECT 'attachments',COUNT(*) FROM skope.report_attachments UNION ALL SELECT 'users',COUNT(*) FROM skope.users"
echo "restored. Previous photos kept at /var/lib/skope/attachments.before-restore-${TS}"
