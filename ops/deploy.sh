#!/usr/bin/env bash
# Run ON host1 (or via Jenkins SSH). Pulls latest images + restarts the stack.
# Closed loop: Jenkins build.sh -> ACR -> (this) pull + up -d -> health.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] || { echo "ERROR: .env missing (copy .env.example and fill DB/admin/ingest secrets)"; exit 1; }

echo "=== git pull ==="
git pull --ff-only || echo "(skip git pull)"

echo "=== compose pull + up ==="
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
# edge nginx caches upstream container IPs at start; recreate it so it re-resolves web/server.
docker compose -f docker-compose.prod.yml up -d --force-recreate nginx

echo "=== status ==="
docker compose -f docker-compose.prod.yml ps

echo "=== health ==="
sleep 5
PORT="${EDGE_PORT:-8090}"
curl -fsS "http://localhost:${PORT}/" >/dev/null 2>&1 && echo "SPA up" || echo "(SPA not ready)"
# 401 on the audit list means the server is up AND correctly requiring admin auth.
code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${PORT}/api/v1/prompts") || code=000
[ "$code" = "401" ] && echo "server up (audit API correctly requires auth)" \
  || echo "(server returned $code — check: docker compose -f docker-compose.prod.yml logs server)"
echo "open: http://<host1>:${PORT}/"
