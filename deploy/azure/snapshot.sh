#!/usr/bin/env bash
# Read-only snapshot of the data a release must preserve: per-table row hashes, the AI usage ledger, photo files and
# settings (secret values never printed). Rows are hashed only up to the ids that existed before the release,
# so rows a release legitimately adds (seeded departments, rules, accounts) do not count as changes.
#   sudo bash snapshot.sh            -> prints "LIMIT <table> <max id>" lines, then the hashes
#   sudo bash snapshot.sh <limits>   -> uses the LIMIT lines of an earlier run (a file holding its output)
set -euo pipefail
TABLES="reports report_attachments report_events similar_pairs incidents users departments routing_rules"
q(){ mysql -N -B skope -e "$1"; }
if [[ $# -ge 1 ]]; then
  declare -A LIM; while read -r tag t m; do [[ "$tag" == LIMIT ]] && LIM[$t]=$m; done < "$1"
  for t in $TABLES; do [[ -n "${LIM[$t]:-}" ]] || { echo "missing LIMIT for $t in $1" >&2; exit 1; }; done
else
  declare -A LIM; for t in $TABLES; do LIM[$t]="$(q "SELECT COALESCE(MAX(id),0) FROM $t")"; echo "LIMIT $t ${LIM[$t]}"; done
fi
cols(){ case "$1" in
  reports) echo "id,student_id,status,route_status,routing_rule_id,assigned_department_id,review_note,incident_id,resolution_text,original_text,updated_at";;
  users) echo "id,username,role,department_id,active,display_name,password_hash";;
  routing_rules) echo "id,category,subcategory,work_name,department_id,contact_phone,source_url,verified_at,active,demo_only";;
  *) echo "*";; esac; }
for t in $TABLES; do
  rows="$(q "SELECT $(cols "$t") FROM $t WHERE id<=${LIM[$t]} ORDER BY id")"
  echo "ROWS $t count=$(printf '%s' "$rows" | grep -c . || true) sha=$(printf '%s' "$rows" | sha256sum | cut -c1-16)"
done
echo "LEDGER $(q "SELECT CONCAT_WS(' ',period_key,llm_calls,embedding_calls,spent_micro_usd,reserved_micro_usd,blocked_calls) FROM ai_usage_budget ORDER BY period_key" | tr '\n' ';')"
echo "PHOTOS files=$(find /var/lib/skope/attachments -type f | wc -l) sha=$(cd /var/lib/skope/attachments && find . -type f -exec sha256sum {} + | sort | sha256sum | cut -c1-16)"
grep -E "^(AI_MODE|EMBEDDING_MODE|AI_MODEL|EMBEDDING_MODEL|AI_REASONING_EFFORT|AI_BUDGET_[A-Z_]+|DEMO_ROUTING_ENABLED|COOKIE_SECURE|PUBLIC_AUTH_LIMIT_ENABLED)=" /etc/skope/skope.env | sort | sed 's/^/SETTING /'
echo "SETTING api_key_present=$(grep -c '^AI_API_KEY=.' /etc/skope/skope.env)"
echo "ENVFILE sha=$(sha256sum /etc/skope/skope.env | cut -c1-16)"
