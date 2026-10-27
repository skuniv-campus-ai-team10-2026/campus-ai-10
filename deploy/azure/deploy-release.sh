#!/usr/bin/env bash
# Installs a release archive made by Build-Release.ps1 (skope.jar + web/ + COMMIT + SHA256SUMS).
# Backs up first, swaps jar and web files, waits for the backend, and rolls back if it does not come up.
#
# Usage: sudo bash /opt/skope/deploy/deploy-release.sh /tmp/skope-release-<commit>.tar.gz
set -euo pipefail
ARCHIVE="${1:?usage: deploy-release.sh <release.tar.gz>}"
[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }
[[ -f "$ARCHIVE" ]] || { echo "not found: $ARCHIVE" >&2; exit 1; }

STAGE="$(mktemp -d)"; trap 'rm -rf "$STAGE"' EXIT
tar -xzf "$ARCHIVE" -C "$STAGE"
( cd "$STAGE" && sha256sum --quiet -c SHA256SUMS ) || { echo "checksum mismatch, nothing changed" >&2; exit 1; }
[[ -f "$STAGE/skope.jar" && -f "$STAGE/web/index.html" && -f "$STAGE/COMMIT" ]] || { echo "incomplete release" >&2; exit 1; }
NEW_COMMIT="$(cat "$STAGE/COMMIT")"
echo "== release ${NEW_COMMIT}"

health() {
    for _ in $(seq 1 90); do
        if curl -fsS --max-time 2 http://127.0.0.1:8080/api/meta >/dev/null 2>&1; then return 0; fi
        sleep 1
    done
    return 1
}

if [[ -f /opt/skope/app/skope.jar ]]; then
    echo "== backup before replacing the running version"
    /opt/skope/deploy/backup.sh
fi

systemctl stop skope || true
# keep the previous version for rollback
[[ -f /opt/skope/app/skope.jar ]] && cp -p /opt/skope/app/skope.jar /opt/skope/app/skope.jar.prev
rm -rf /opt/skope/web.prev; [[ -d /opt/skope/web ]] && cp -a /opt/skope/web /opt/skope/web.prev
[[ -f /opt/skope/RELEASE ]] && cp -p /opt/skope/RELEASE /opt/skope/RELEASE.prev

install -o skope -g skope -m 0640 "$STAGE/skope.jar" /opt/skope/app/skope.jar
rm -rf /opt/skope/web.new && cp -a "$STAGE/web" /opt/skope/web.new && chown -R root:root /opt/skope/web.new && chmod -R a+rX /opt/skope/web.new
rm -rf /opt/skope/web && mv /opt/skope/web.new /opt/skope/web
echo "${NEW_COMMIT}" > /opt/skope/RELEASE

systemctl start skope
if health; then
    echo "== running ${NEW_COMMIT}"
    curl -fsS http://127.0.0.1:8080/api/meta; echo
    exit 0
fi

echo "!! backend did not become healthy; rolling back" >&2
journalctl -u skope -n 60 --no-pager >&2 || true
systemctl stop skope || true
if [[ -f /opt/skope/app/skope.jar.prev ]]; then
    cp -p /opt/skope/app/skope.jar.prev /opt/skope/app/skope.jar
    rm -rf /opt/skope/web && cp -a /opt/skope/web.prev /opt/skope/web
    [[ -f /opt/skope/RELEASE.prev ]] && cp -p /opt/skope/RELEASE.prev /opt/skope/RELEASE
    systemctl start skope
    health && echo "rolled back to $(cat /opt/skope/RELEASE 2>/dev/null)" >&2
fi
# Note: Flyway migrations of the failed version may already have run; restore.sh can return the DB to the pre-deploy backup.
exit 1
