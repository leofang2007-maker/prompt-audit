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

# Idempotency key. The IDE gives a stable request_set_id (UUID) per submission — prefer it
# (PROMPT_AUDIT_EVENT_ID). Fallback for tools without one: sha256(session|YYYY-MM-DDTHH:MM|prompt),
# minute-truncated so an IDE double-fire / drain retry shares an event_id. The gateway dedups on it.
ts_min="$(printf '%s' "$ts" | cut -c1-16)"
event_id="${PROMPT_AUDIT_EVENT_ID:-$(printf '%s' "${session}|${ts_min}|${prompt}" | shasum -a 256 | cut -d' ' -f1)}"

# Identity (v1.0.2). The real IDE hook reads these from its input (.extra.user.*); a generic shell
# can't, so they're env-overridable and default to empty (the gateway is fail-open on all of them).
# transcript_path is the abs path to this session's conversation JSONL, if the tool exposes one.

# Build JSON safely (jq escapes newlines/quotes/unicode in the prompt).
payload="$(jq -n \
  --arg event_id "$event_id" \
  --arg timestamp "$ts" \
  --arg session_id "$session" \
  --arg user_email "$email" \
  --arg user_name "${PROMPT_AUDIT_USER_NAME:-}" \
  --arg user_uid "${PROMPT_AUDIT_USER_UID:-}" \
  --arg org_id "${PROMPT_AUDIT_ORG_ID:-}" \
  --arg org_name "${PROMPT_AUDIT_ORG_NAME:-}" \
  --arg repo "$repo" \
  --arg branch "$branch" \
  --arg cwd "$PWD" \
  --arg transcript_path "${PROMPT_AUDIT_TRANSCRIPT:-}" \
  --arg hostname "$(hostname)" \
  --arg prompt "$prompt" \
  '{event_id:$event_id, timestamp:$timestamp, session_id:$session_id, user_email:$user_email,
    user_name:$user_name, user_uid:$user_uid, org_id:$org_id, org_name:$org_name,
    repo:$repo, branch:$branch, cwd:$cwd, transcript_path:$transcript_path,
    hostname:$hostname, prompt:$prompt}')"

# Fire-and-forget: never block the developer if the audit service is down.
curl -sS --max-time 3 \
  -X POST "$BASE/api/v1/prompts" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "$payload" >/dev/null 2>&1 || echo "prompt-audit: report failed (ignored)" >&2
