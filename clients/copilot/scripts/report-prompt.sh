#!/bin/sh
# =============================================================================
# prompt-audit / GitHub Copilot adapter — report-prompt.sh
# Hook: UserPromptSubmit  (Copilot CLI / cloud agent / VS Code agent-mode [Preview])
# Purpose: forward each submitted prompt (+ enriched identity) to the audit server.
#
# ⚠️ SURFACE COVERAGE: this hook fires ONLY on Copilot's AGENT surfaces (CLI, cloud agent, VS Code
#    agent mode). Classic Copilot Chat (ask/edit), inline completions, and JetBrains Copilot expose NO
#    client hook and cannot be captured this way. See README.md — do not assume full coverage.
#
# Design (spec 0006): non-blocking (always exit 0), fail-open, spool-and-drain.
# Deps: jq, curl (if missing, pass through).
# =============================================================================

INPUT=$(cat)
command -v jq   >/dev/null 2>&1 || exit 0
command -v curl >/dev/null 2>&1 || exit 0

SELF_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
_BUNDLED_ROOT=$(cd "$SELF_DIR/.." 2>/dev/null && pwd)
: "${PROMPT_AUDIT_DATA:=$HOME/.prompt-audit}"
_ENV_URL="$PROMPT_AUDIT_GATEWAY_URL"; _ENV_TOKEN="$PROMPT_AUDIT_GATEWAY_TOKEN"
EVENT_CWD=$(printf '%s' "$INPUT" | jq -r '.cwd // empty')
for _cfg in \
  "${EVENT_CWD:+$EVENT_CWD/.prompt-audit.conf}" \
  "$PROMPT_AUDIT_DATA/config.conf" \
  "$HOME/.prompt-audit.conf" \
  "${_BUNDLED_ROOT:+$_BUNDLED_ROOT/config.conf}"
do
  if [ -n "$_cfg" ] && [ -f "$_cfg" ]; then . "$_cfg"; break; fi
done
[ -n "$_ENV_URL" ]   && PROMPT_AUDIT_GATEWAY_URL="$_ENV_URL"
[ -n "$_ENV_TOKEN" ] && PROMPT_AUDIT_GATEWAY_TOKEN="$_ENV_TOKEN"
GATEWAY_URL="${PROMPT_AUDIT_GATEWAY_URL:-https://audit.example.com/api/v1/prompts}"
GATEWAY_TOKEN="${PROMPT_AUDIT_GATEWAY_TOKEN:-}"
case "$GATEWAY_URL" in *audit.example.com*) exit 0 ;; esac

PROMPT=$(printf '%s' "$INPUT"  | jq -r '.prompt // empty')
[ -z "$PROMPT" ] && exit 0
SESSION=$(printf '%s' "$INPUT" | jq -r '.sessionId // .session_id // empty')
CWD=$(printf '%s' "$INPUT"     | jq -r '.cwd // empty')
CC_TS=$(printf '%s' "$INPUT"   | jq -r '.timestamp // empty')

TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
[ -n "$CC_TS" ] && TS="$CC_TS"        # Copilot provides a timestamp; use it when present
HOSTNAME=$(hostname 2>/dev/null || echo "unknown")

# identity: Copilot hook JSON carries none → env → git → OS user (fail-open)
EMAIL="${PROMPT_AUDIT_USER_EMAIL:-}"; USER_NAME="${PROMPT_AUDIT_USER_NAME:-}"
USER_UID="${PROMPT_AUDIT_USER_UID:-}"; ORG_ID="${PROMPT_AUDIT_ORG_ID:-}"; ORG_NAME="${PROMPT_AUDIT_ORG_NAME:-}"
if [ -n "$CWD" ] && command -v git >/dev/null 2>&1; then
  [ -z "$EMAIL" ]     && EMAIL=$(git -C "$CWD" config --get user.email 2>/dev/null)
  [ -z "$USER_NAME" ] && USER_NAME=$(git -C "$CWD" config --get user.name 2>/dev/null)
fi
[ -z "$USER_NAME" ] && USER_NAME=$(id -un 2>/dev/null || printf '%s' "${USER:-}")

REPO=""; BRANCH=""
if [ -n "$CWD" ] && { [ -d "$CWD/.git" ] || [ -f "$CWD/.git" ]; } && command -v git >/dev/null 2>&1; then
  REPO=$(  git -C "$CWD" config --get remote.origin.url 2>/dev/null | sed 's#\.git$##;s#.*[/:]##')
  BRANCH=$(git -C "$CWD" symbolic-ref --short HEAD       2>/dev/null)
fi

# event_id: session + timestamp + prompt fingerprint (no dedicated per-submit id)
_FP="${SESSION}|${TS}|${PROMPT}"
if command -v shasum >/dev/null 2>&1; then EVENT_ID=$(printf '%s' "$_FP" | shasum -a 256 | cut -d' ' -f1)
elif command -v sha256sum >/dev/null 2>&1; then EVENT_ID=$(printf '%s' "$_FP" | sha256sum | cut -d' ' -f1)
else EVENT_ID=""; fi

PAYLOAD=$(jq -cn \
  --arg ts "$TS" --arg eid "$EVENT_ID" --arg sid "$SESSION" \
  --arg email "$EMAIL" --arg uname "$USER_NAME" --arg uuid_ "$USER_UID" \
  --arg org_id "$ORG_ID" --arg org_name "$ORG_NAME" \
  --arg repo "$REPO" --arg branch "$BRANCH" --arg cwd "$CWD" \
  --arg host "$HOSTNAME" --arg prompt "$PROMPT" \
  '{event_id:$eid,timestamp:$ts,session_id:$sid,user_email:$email,user_name:$uname,
    user_uid:$uuid_,org_id:$org_id,org_name:$org_name,repo:$repo,branch:$branch,
    cwd:$cwd,hostname:$host,prompt:$prompt}')

FALLBACK_DIR="$PROMPT_AUDIT_DATA/failed"
if ! curl -sS -X POST "$GATEWAY_URL" \
      -H "Content-Type: application/json" -H "Authorization: Bearer $GATEWAY_TOKEN" \
      --max-time 2 --data-binary "$PAYLOAD" >/dev/null 2>&1; then
  mkdir -p "$FALLBACK_DIR" 2>/dev/null
  printf '%s\n' "$PAYLOAD" >> "$FALLBACK_DIR/pending-$(date +%Y-%m-%d).jsonl" 2>/dev/null
fi

exit 0   # observe-only: never block
