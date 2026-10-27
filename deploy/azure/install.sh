#!/usr/bin/env bash
# One-time server setup for the SKOPE submission VM (Ubuntu 24.04 LTS).
# Installs Java 25 (Temurin JRE), MySQL 8 (local only), Caddy (automatic HTTPS), creates the service user,
# folders, database and secrets, and installs systemd units. Safe to run again: existing secrets/data are kept.
#
# Usage (on the VM, from the folder that contains this script):
#   sudo bash install.sh <public-hostname>
#   e.g. sudo bash install.sh skope-team10.koreacentral.cloudapp.azure.com
set -euo pipefail

HOST="${1:?usage: sudo bash install.sh <public-hostname>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }
[[ "$HOST" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ ]] || { echo "invalid hostname: $HOST" >&2; exit 1; }
. /etc/os-release
[[ "${ID}" == "ubuntu" ]] || { echo "Ubuntu expected, found ${ID}" >&2; exit 1; }
[[ "${VERSION_ID}" == "24.04" ]] || echo "warning: tested on Ubuntu 24.04, found ${VERSION_ID}" >&2

echo "== packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl gnupg apt-transport-https debian-keyring debian-archive-keyring openssl ufw
install -d -m 0755 /etc/apt/keyrings
# Temurin (Eclipse Adoptium) for Java 25
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb ${VERSION_CODENAME} main" > /etc/apt/sources.list.d/adoptium.list
# Caddy (official stable repository)
curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
apt-get update -y
apt-get install -y temurin-25-jre mysql-server caddy
java -version 2>&1 | grep -q 'version "25' || { echo "Java 25 not active" >&2; exit 1; }
timedatectl set-timezone Asia/Seoul

echo "== MySQL: local connections only"
cat > /etc/mysql/mysql.conf.d/zz-skope.cnf <<'EOF'
[mysqld]
bind-address = 127.0.0.1
mysqlx = OFF
character-set-server = utf8mb4
collation-server = utf8mb4_0900_ai_ci
EOF
systemctl enable mysql
systemctl restart mysql

echo "== user and folders"
id skope >/dev/null 2>&1 || useradd --system --home-dir /opt/skope --shell /usr/sbin/nologin skope
install -d -o root  -g root  -m 0755 /opt/skope /opt/skope/web /opt/skope/deploy
install -d -o skope -g skope -m 0750 /opt/skope/app /var/lib/skope /var/lib/skope/attachments
install -d -o root  -g root  -m 0700 /etc/skope /var/backups/skope
cp "$HERE"/*.sh /opt/skope/deploy/ && chmod 0750 /opt/skope/deploy/*.sh

echo "== database and secrets (first run only)"
if [[ ! -f /etc/skope/skope.env ]]; then
    DB_PASSWORD="$(openssl rand -hex 24)"
    DEMO_PASSWORD="$(openssl rand -hex 12)"
    mysql <<SQL
CREATE DATABASE IF NOT EXISTS skope CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'skope'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
ALTER USER 'skope'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
GRANT ALL ON skope.* TO 'skope'@'localhost';
SQL
    sed -e "s/__DB_PASSWORD__/${DB_PASSWORD}/" -e "s/__DEMO_PASSWORD__/${DEMO_PASSWORD}/" "$HERE/skope.env.example" > /etc/skope/skope.env
    chmod 0600 /etc/skope/skope.env
    printf 'SERVER DEMO ACCOUNTS. Do not publish this file.\nstudent1 / %s\nstudent2 / %s\nadmin / %s\n' \
        "$DEMO_PASSWORD" "$DEMO_PASSWORD" "$DEMO_PASSWORD" > /etc/skope/demo-accounts.txt
    chmod 0600 /etc/skope/demo-accounts.txt
    unset DB_PASSWORD DEMO_PASSWORD
else
    echo "kept existing /etc/skope/skope.env"
fi

echo "== Caddy (HTTPS for ${HOST})"
sed "s/__HOST__/${HOST}/g" "$HERE/Caddyfile.template" > /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
systemctl enable caddy
systemctl restart caddy
echo "${HOST}" > /etc/skope/hostname

echo "== systemd units"
install -m 0644 "$HERE/skope.service" "$HERE/skope-backup.service" "$HERE/skope-backup.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable skope.service          # starts on boot; first start happens in deploy-release.sh
systemctl enable --now skope-backup.timer

echo "== host firewall (Azure NSG is the first layer)"
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "done. Next: upload a release archive and run: sudo bash /opt/skope/deploy/deploy-release.sh <archive>"
