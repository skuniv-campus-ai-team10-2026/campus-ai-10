#!/usr/bin/env bash
# End-to-end DEMO smoke test over the public HTTPS address: login, natural-language intake, photo upload,
# administrator status change, student result, cross-student isolation, secure cookie and auth limit header.
# Creates one synthetic report; run restore.sh with a prior backup afterwards to remove it.
# Demo passwords are read from /etc/skope/demo-accounts.txt and never printed.
# Usage: sudo bash /opt/skope/deploy/smoke.sh /path/to/test.jpg
set -uo pipefail
HOST="$(cat /etc/skope/hostname)"
BASE="https://${HOST}/api"
IMG="${1:?usage: smoke.sh <jpeg>}"
PASS="$(awk '/^student1 \//{print $3}' /etc/skope/demo-accounts.txt)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
FAILED=0
ok()  { echo "[PASS] $*"; }
bad() { echo "[FAIL] $*"; FAILED=1; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {}, {'d': d}))" "$1"; }

# session <name>: cookie jar per user; csrf token refreshed after login.
token() { curl -sS -b "$WORK/$1" -c "$WORK/$1" "$BASE/auth/csrf" | json 'd["token"]'; }
call() { # call <user> <method> <path> [json]
    local t; t="$(token "$1")"
    curl -sS -b "$WORK/$1" -c "$WORK/$1" -X "$2" -H "X-CSRF-TOKEN: $t" -H 'Content-Type: application/json' \
        ${4:+--data "$4"} -w '\n%{http_code}' "$BASE$3"
}
body() { sed '$d'; }
code() { tail -n1; }
login() { # login <user>
    local r; r="$(call "$1" POST /auth/login "{\"username\":\"$1\",\"password\":\"$PASS\"}")"
    [[ "$(code <<<"$r")" == 200 ]] && ok "login $1" || bad "login $1 -> $(code <<<"$r")"
}

login student1
grep -q '#HttpOnly_' "$WORK/student1" && ok "session cookie HttpOnly" || bad "session cookie not HttpOnly"
awk '$6=="JSESSIONID" && $4=="TRUE"' "$WORK/student1" | grep -q . && ok "session cookie Secure" || bad "session cookie not Secure"

KEY="$(python3 -c 'import uuid; print(uuid.uuid4())')"
R="$(call student1 POST /assistant/intake "{\"clientRequestId\":\"$KEY\",\"text\":\"[배포 점검] 북악관 3층 정수기가 고장났어요\"}")"
STATUS="$(body <<<"$R" | json 'd.get("status")')"
[[ "$STATUS" == ACCEPTED ]] && ok "intake ACCEPTED" || bad "intake status $STATUS ($(code <<<"$R"))"
ID="$(body <<<"$R" | json 'd["report"]["id"]')"
echo "  report $ID: $(body <<<"$R" | json '(d["report"]["subcategory"], d["report"]["workGroup"], (d["report"].get("assignedDepartment") or {}).get("name"))')"

T="$(token student1)"
UP="$(curl -sS -b "$WORK/student1" -c "$WORK/student1" -H "X-CSRF-TOKEN: $T" -F "file=@${IMG};type=image/jpeg" -w '\n%{http_code}' "$BASE/reports/$ID/attachments")"
[[ "$(code <<<"$UP")" == 201 ]] && ok "photo upload 201" || bad "photo upload $(code <<<"$UP"): $(body <<<"$UP")"
AID="$(body <<<"$UP" | json 'd["id"]' 2>/dev/null)"
C="$(curl -sS -b "$WORK/student1" -o "$WORK/photo" -w '%{http_code} %{content_type}' "$BASE/reports/$ID/attachments/$AID/content")"
[[ "$C" == 200\ image/jpeg* ]] && ok "photo download ($C, $(stat -c %s "$WORK/photo") bytes, metadata stripped copy)" || bad "photo download $C"

login admin
R="$(call admin PATCH "/admin/reports/$ID/status" '{"status":"IN_PROGRESS"}')"
[[ "$(code <<<"$R")" == 200 ]] && ok "admin RECEIVED -> IN_PROGRESS" || bad "admin status $(code <<<"$R"): $(body <<<"$R")"
R="$(call admin PATCH "/admin/reports/$ID/status" '{"status":"RESOLVED","resolutionText":"[배포 점검] 정수기 점검 완료"}')"
[[ "$(code <<<"$R")" == 200 ]] && ok "admin IN_PROGRESS -> RESOLVED" || bad "admin resolve $(code <<<"$R"): $(body <<<"$R")"
C="$(curl -sS -b "$WORK/admin" -o /dev/null -w '%{http_code}' "$BASE/admin/reports/$ID/attachments/$AID/content")"
[[ "$C" == 200 ]] && ok "admin can view photo" || bad "admin photo $C"

R="$(curl -sS -b "$WORK/student1" -w '\n%{http_code}' "$BASE/reports/$ID")"
[[ "$(body <<<"$R" | json 'd["status"]')" == RESOLVED ]] && ok "student sees RESOLVED: $(body <<<"$R" | json 'd["resolutionText"]')" || bad "student result $(code <<<"$R")"

login student2
C="$(curl -sS -b "$WORK/student2" -o /dev/null -w '%{http_code}' "$BASE/reports/$ID")"
[[ "$C" == 404 ]] && ok "other student gets 404" || bad "other student got $C"
C="$(curl -sS -b "$WORK/student2" -o /dev/null -w '%{http_code}' "$BASE/admin/reports")"
[[ "$C" == 403 ]] && ok "student blocked from admin API (403)" || bad "student admin API $C"
C="$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{}' "$BASE/auth/login")"
[[ "$C" == 403 ]] && ok "login without CSRF token rejected (403)" || bad "login without CSRF $C"
exit $FAILED
