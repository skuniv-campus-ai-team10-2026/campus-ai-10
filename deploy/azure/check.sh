#!/usr/bin/env bash
# Read-only health report for the SKOPE VM. Prints PASS/FAIL lines; changes nothing.
# Usage: sudo bash /opt/skope/deploy/check.sh
set -uo pipefail
HOST="$(cat /etc/skope/hostname 2>/dev/null || echo unknown)"
ok()   { echo "[PASS] $*"; }
bad()  { echo "[FAIL] $*"; FAILED=1; }
FAILED=0

for svc in mysql caddy skope; do
    systemctl is-active --quiet "$svc" && ok "$svc running" || bad "$svc not running"
    systemctl is-enabled --quiet "$svc" && ok "$svc starts on boot" || bad "$svc not enabled at boot"
done
systemctl is-active --quiet skope-backup.timer && ok "daily backup timer active" || bad "backup timer inactive"

# Only Caddy (80/443) and SSH may listen publicly; MySQL and the backend stay on loopback.
LISTEN="$(ss -Hltn | awk '{print $4}')"
echo "$LISTEN" | grep -Eq '^(0\.0\.0\.0|\*|\[::\]):3306$' && bad "MySQL listens publicly" || ok "MySQL bound to loopback"
echo "$LISTEN" | grep -Eq '^(0\.0\.0\.0|\*|\[::\]):8080$' && bad "backend listens publicly" || ok "backend bound to loopback"
echo "$LISTEN" | grep -Eq ':443$' && ok "HTTPS port listening" || bad "nothing on 443"

META="$(curl -fsS --max-time 10 "https://${HOST}/api/meta" 2>/dev/null)" && ok "https://${HOST}/api/meta ${META}" || bad "https://${HOST}/api/meta unreachable"
CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://${HOST}/")" ; [[ "$CODE" == 200 ]] && ok "web page 200" || bad "web page ${CODE}"
CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://${HOST}/api/reports")" ; [[ "$CODE" == 401 ]] && ok "API requires login (401)" || bad "unauthenticated /api/reports returned ${CODE}"
CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://${HOST}/")" ; [[ "$CODE" =~ ^30[178]$ ]] && ok "http redirects to https" || bad "http returned ${CODE}"

echo "release: $(cat /opt/skope/RELEASE 2>/dev/null || echo none)"
mysql -N -e "SELECT CONCAT('flyway latest V', MAX(CAST(version AS UNSIGNED))) FROM skope.flyway_schema_history WHERE success=1" 2>/dev/null
mysql -N -e "SELECT 'reports',COUNT(*) FROM skope.reports UNION ALL SELECT 'attachments',COUNT(*) FROM skope.report_attachments UNION ALL SELECT 'users',COUNT(*) FROM skope.users" 2>/dev/null
mysql -N -e "SELECT CONCAT('ai budget ',period_key,': llm=',llm_calls,' emb=',embedding_calls,' spent_usd=',spent_micro_usd/1000000,' reserved_usd=',reserved_micro_usd/1000000) FROM skope.ai_usage_budget" 2>/dev/null
echo "photo files: $(find /var/lib/skope/attachments -type f ! -name '*.part' | wc -l)"
if [[ -d /var/backups/skope/latest ]]; then
    AGE=$(( ( $(date +%s) - $(stat -c %Y /var/backups/skope/latest/SHA256SUMS) ) / 3600 ))
    (( AGE <= 26 )) && ok "latest backup ${AGE}h old" || bad "latest backup ${AGE}h old"
else bad "no backup yet"; fi
df -h / /var/lib/skope | tail -n +2
exit $FAILED
