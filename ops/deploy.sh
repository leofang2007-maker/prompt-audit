#!/usr/bin/env bash
# Run on the deploy host. Pulls the latest image + restarts the (single-container) stack + health.
# One-time: put the server behind your TLS reverse proxy (see ops/nginx-prompt-audit.site).
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] || { echo "ERROR: .env missing (copy .env.example and fill DB/admin/ingest secrets)"; exit 1; }
set -a; . ./.env; set +a

echo "=== git pull ==="
git pull --ff-only || echo "(skip git pull)"

echo "=== compose pull + up ==="
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d

echo "=== status ==="
docker compose -f docker-compose.prod.yml ps

echo "=== health ==="
sleep 5
PORT="${APP_PORT:-8091}"
curl -fsS "http://127.0.0.1:${PORT}/" >/dev/null 2>&1 && echo "SPA up" || echo "(SPA not ready)"
# 401 on the audit list means the server is up AND correctly requiring admin auth.
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/api/v1/prompts") || code=000
[ "$code" = "401" ] && echo "server up (audit API correctly requires auth)" \
  || echo "(server returned $code — check: docker compose -f docker-compose.prod.yml logs server)"
echo "open: http://audit.example.com/  (via shared nginx → 127.0.0.1:${PORT})"
