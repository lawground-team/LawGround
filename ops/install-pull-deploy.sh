#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID != 0 || $# != 1 || ! $1 =~ ^[a-f0-9]{40}$ ]]; then
    printf 'Usage: sudo %s CURRENTLY_DEPLOYED_SHA\n' "$0" >&2
    exit 2
fi

here=$(cd -- "$(dirname -- "$0")" && pwd)
original=/srv/dis-docker-data/lawground
base=/srv/dis-docker-data/lawground-deploy
baseline="$base/releases/$1"
mountpoint -q /srv/dis-docker-data
[[ -f "$original/app.jar" && -d "$original/source/ai-service/app" && -x "$original/venv/bin/python" ]]
install -d -m 0755 -o root -g root "$base" "$base/releases" "$base/envs"
install -d -m 0700 -o root -g root "$base/backups"
if [[ ! -e "$base/current" ]]; then
    install -d -m 0755 -o root -g root "$baseline"
    ln -s "$original/app.jar" "$baseline/app.jar"
    ln -s "$original/source/ai-service" "$baseline/ai-service"
    ln -s "$original/venv" "$baseline/venv"
    printf '{"baseline":"%s","previous":"%s"}\n' "$1" "$1" >"$base/state.json"
    chmod 0600 "$base/state.json"
    ln -s "$baseline" "$base/current"
fi

install -d -m 0755 /usr/local/libexec
install -m 0755 "$here/pull-deploy.py" /usr/local/libexec/lawground-pull-deploy
install -d -m 0755 /etc/systemd/system/lawground-web.service.d /etc/systemd/system/lawground-ai.service.d
cat >/etc/systemd/system/lawground-web.service.d/deploy-release.conf <<'UNIT'
[Service]
ExecStart=
ExecStart=/usr/bin/java -Xms128m -Xmx1024m -jar /srv/dis-docker-data/lawground-deploy/current/app.jar
UNIT
cat >/etc/systemd/system/lawground-ai.service.d/deploy-release.conf <<'UNIT'
[Service]
WorkingDirectory=/srv/dis-docker-data/lawground-deploy/current/ai-service
ExecStart=
ExecStart=/srv/dis-docker-data/lawground-deploy/current/venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 18001 --no-access-log
UNIT
cat >/etc/systemd/system/lawground-pull-deploy.service <<'UNIT'
[Unit]
Description=Deploy a successful LawGround main build from GitHub
Wants=network-online.target
After=network-online.target docker.service
RequiresMountsFor=/srv/dis-docker-data/lawground-deploy

[Service]
Type=oneshot
ExecStart=/usr/bin/flock -n /run/lock/lawground-pull-deploy /usr/local/libexec/lawground-pull-deploy
TimeoutStartSec=25min
UMask=0022
UNIT
cat >/etc/systemd/system/lawground-pull-deploy.timer <<'UNIT'
[Unit]
Description=Check GitHub for a successful LawGround deployment

[Timer]
OnBootSec=2min
OnUnitInactiveSec=3min
Unit=lawground-pull-deploy.service

[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemd-analyze verify lawground-pull-deploy.{service,timer} lawground-{web,ai}.service
systemctl enable --now lawground-pull-deploy.timer
