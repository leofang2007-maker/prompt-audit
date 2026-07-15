#!/usr/bin/env bash
# Example client hook: report one prompt to the prompt-audit service.
#
# Reads the prompt from $1 or stdin, gathers lightweight context, and POSTs to /api/v1/prompts
# using the INGEST token (write-only). Designed to be wired into a coding tool's "prompt submit"
# hook (e.g. Claude Code UserPromptSubmit, a Qoder client hook, a git commit-msg hook, etc.).
#
# Config via env:
#   PROMPT_AUDIT_URL     e.g. https://audit.theprismatlas.com   (base URL of the service)
#   PROMPT_AUDIT_TOKEN   the INGEST_TOKEN
#   PROMPT_AUDIT_EMAIL   optional; defaults to `git config user.email`
set -euo pipefail

BASE="${PROMPT_AUDIT_URL:?set PROMPT_AUDIT_URL}"
TOKEN="${PROMPT_AUDIT_TOKEN:?set PROMPT_AUDIT_TOKEN}"

prompt="${1:-$(cat)}"
[ -n "$prompt" ] || { echo "empty prompt, nothing to report" >&2; exit 0; }

email="${PROMPT_AUDIT_EMAIL:-$(git config user.email 2>/dev/null || echo unknown)}"
repo="$(basename "$(git rev-parse --show-toplevel 2>/dev/null || echo "$PWD")")"
branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '')"
ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
session="${PROMPT_AUDIT_SESSION:-$$}"

# Deterministic idempotency key: an IDE may fire the same submission twice (~1-2s apart) and drains
# may retry — all must map to ONE audit row. Truncate the timestamp to the minute so those retries
# share an event_id; the gateway dedups on it. Contract: sha256(session|YYYY-MM-DDTHH:MM|prompt), hex.
ts_min="$(printf '%s' "$ts" | cut -c1-16)"
event_id="$(printf '%s' "${session}|${ts_min}|${prompt}" | shasum -a 256 | cut -d' ' -f1)"

# Build JSON safely (jq escapes newlines/quotes/unicode in the prompt).
payload="$(jq -n \
  --arg event_id "$event_id" \
  --arg timestamp "$ts" \
  --arg session_id "$session" \
  --arg user_email "$email" \
  --arg repo "$repo" \
  --arg branch "$branch" \
  --arg cwd "$PWD" \
  --arg hostname "$(hostname)" \
  --arg prompt "$prompt" \
  '{event_id:$event_id, timestamp:$timestamp, session_id:$session_id, user_email:$user_email,
    repo:$repo, branch:$branch, cwd:$cwd, hostname:$hostname, prompt:$prompt}')"

# Fire-and-forget: never block the developer if the audit service is down.
curl -sS --max-time 3 \
  -X POST "$BASE/api/v1/prompts" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "$payload" >/dev/null 2>&1 || echo "prompt-audit: report failed (ignored)" >&2
