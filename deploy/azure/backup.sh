#!/usr/bin/env bash
# Backup = consistent MySQL dump (all tables, incl. the AI usage ledger) + photo folder + release id,
# with checksums. Kept on the VM for 14 runs; copy off the VM with Pull-Backup.ps1.
# The DB dump is taken first and the photos right after, so a backup may contain a few extra photo files
# (never fewer referenced ones for completed uploads); extra files are harmless.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run as root" >&2; exit 1; }
KEEP="${KEEP:-14}"
TS="$(date +%Y%m%d-%H%M%S)"
DEST="/var/backups/skope/${TS}"
install -d -m 0700 "$DEST"

mysqldump --single-transaction --routines --triggers --databases skope | gzip -9 > "$DEST/skope.sql.gz"
tar -C /var/lib/skope -czf "$DEST/attachments.tar.gz" attachments
cp -p /opt/skope/RELEASE "$DEST/RELEASE" 2>/dev/null || echo "none" > "$DEST/RELEASE"
mysql -N -e "SELECT 'reports',COUNT(*) FROM skope.reports UNION ALL SELECT 'attachments',COUNT(*) FROM skope.report_attachments UNION ALL SELECT 'users',COUNT(*) FROM skope.users" > "$DEST/COUNTS"
( cd "$DEST" && sha256sum skope.sql.gz attachments.tar.gz RELEASE COUNTS > SHA256SUMS )
chmod 0600 "$DEST"/*
ln -sfn "$DEST" /var/backups/skope/latest

# keep the newest $KEEP backups
ls -1d /var/backups/skope/20*/ 2>/dev/null | sort | head -n -"$KEEP" | xargs -r rm -rf
echo "backup ${DEST}"
cat "$DEST/COUNTS"
