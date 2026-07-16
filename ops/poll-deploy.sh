#!/usr/bin/env bash
# Optional pull-based CD for a host with no public inbound (so CI can't push a deploy to it).
# Pulls the image tag configured in .env and restarts ONLY if the image actually changed.
# Run from cron, e.g. every 5 min:   */5 * * * * /opt/prompt-audit/ops/poll-deploy.sh >> /var/log/promptaudit-deploy.log 2>&1
#
# Reproducible deploys: pin TAG=1.2.3 in .env and bump it per release (then this pulls that tag).
# Rolling deploys: set TAG=latest in .env and this auto-updates whenever CI pushes a new :latest.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "$(date -u +%FT%TZ) ERROR: .env missing"; exit 1; }
set -a; . ./.env; set +a

C="docker compose -f docker-compose.prod.yml"
before=$($C images -q server 2>/dev/null || true)
$C pull -q server
after=$($C images -q server 2>/dev/null || true)

if [ "$before" = "$after" ]; then
  exit 0   # no change, nothing to do
fi

echo "$(date -u +%FT%TZ) new image ($before -> $after) — redeploying"
$C up -d
sleep 5
PORT="${APP_PORT:-8091}"
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/api/v1/prompts") || code=000
echo "$(date -u +%FT%TZ) deployed; audit API returned $code (401 = up + auth required)"
