#!/usr/bin/env bash
# Read-only release checks over the public HTTPS address and the database. Logs in and reads only: it files no
# report, so it never calls the AI provider (safe in LIVE mode). Passwords are never printed.
#   sudo bash verify-readonly.sh <expected full commit>
# Note: logging in replaces an existing session of the same account (single-login policy).
set -uo pipefail
EXPECT="${1:?expected commit}"
HOST="$(cat /etc/skope/hostname)"; BASE="https://${HOST}/api"
PASS="$(awk '/^student1 \//{print $3}' /etc/skope/demo-accounts.txt)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
FAILED=0; ok(){ echo "[PASS] $*"; }; bad(){ echo "[FAIL] $*"; FAILED=1; }
q(){ mysql -N -B skope -e "$1"; }
json(){ python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {}, {'d': d}))" "$1"; }
token(){ curl -sS -b "$WORK/$1" -c "$WORK/$1" "$BASE/auth/csrf" | json 'd["token"]'; }
login(){ local t; t="$(token "$1")"; curl -sS -o /dev/null -w '%{http_code}' -b "$WORK/$1" -c "$WORK/$1" -X POST -H "X-CSRF-TOKEN: $t" -H 'Content-Type: application/json' --data "{\"username\":\"$1\",\"password\":\"$PASS\"}" "$BASE/auth/login"; }
get(){ curl -sS -b "$WORK/$1" -w '\n%{http_code}' "$BASE$2"; }

[[ "$(cat /opt/skope/RELEASE)" == "$EXPECT" ]] && ok "running $EXPECT" || bad "running $(cat /opt/skope/RELEASE), expected $EXPECT"
ok "flyway latest V$(q "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")"
[[ "$(q "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0")" == 0 ]] && ok "no failed migration" || bad "failed migration rows present"
META="$(curl -sS "$BASE/meta")"; echo "  meta: $META"
echo "  departments=$(q "SELECT COUNT(*) FROM departments") department_accounts=$(q "SELECT COUNT(*) FROM users WHERE role='DEPT_ADMIN'") work_group_rules=$(q "SELECT COUNT(*) FROM routing_rules WHERE category='WORK_GROUP'") manual_rules=$(q "SELECT COUNT(*) FROM routing_rules WHERE category='MANUAL'")"
n="$(q "SELECT COUNT(*) FROM routing_rules WHERE category IN ('WORK_GROUP','MANUAL') AND (demo_only=FALSE OR contact_phone IS NOT NULL OR verified_at IS NOT NULL)")"
[[ "$n" == 0 ]] && ok "work-group and manual rules are demo-only, unverified, without contact" || bad "non-demo group/manual rules: $n"
for d in "학생처 학생상담센터" "서경인권센터"; do
  n="$(q "SELECT (SELECT COUNT(*) FROM routing_rules rr JOIN departments x ON x.id=rr.department_id WHERE x.name='$d')+(SELECT COUNT(*) FROM users u JOIN departments x ON x.id=u.department_id WHERE x.name='$d')")"
  [[ "$n" == 0 ]] && ok "$d: no rule, no account" || bad "$d: rules+accounts=$n"
done
[[ "$(login student2)" == 200 ]] || bad "student2 login"
S="$(get student2 /report-types | sed '$d')"
[[ "$(json 'any(i["category"] in ("MANUAL","WORK_GROUP") or i.get("manualOnly") for i in d["items"])' <<<"$S")" == False ]] && ok "student type list: report types only ($(json 'len(d["items"])' <<<"$S"))" || bad "student type list exposes admin-only rules"
[[ "$(login admin)" == 200 ]] || bad "admin login"
A="$(get admin /report-types | sed '$d')"
[[ "$(json 'len(d["items"])-sum(1 for i in d["items"] if i.get("manualOnly"))' <<<"$A")" == "$(json 'len(d["items"])' <<<"$S")" ]] && ok "supervisor list: report types + $(json 'sum(1 for i in d["items"] if i.get("manualOnly"))' <<<"$A") manual-only" || bad "supervisor list differs beyond manual-only rules"
[[ "$(get student2 /admin/reports | tail -n1)" == 403 ]] && ok "student blocked from admin API (403)" || bad "student admin API not 403"
C="$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{}' "$BASE/auth/login")"; [[ "$C" == 403 ]] && ok "login without CSRF token rejected (403)" || bad "login without CSRF $C"
grep -q '#HttpOnly_' "$WORK/student2" && ok "session cookie HttpOnly" || bad "session cookie not HttpOnly"
awk '$6=="JSESSIONID" && $4=="TRUE"' "$WORK/student2" | grep -q . && ok "session cookie Secure" || bad "session cookie not Secure"
exit $FAILED
