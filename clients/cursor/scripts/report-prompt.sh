#!/bin/sh
# =============================================================================
# prompt-audit / Cursor adapter — report-prompt.sh
# Hook: beforeSubmitPrompt  (Cursor 1.7+)
# Purpose: forward each submitted prompt (+ identity) to the audit server.
#
# Design (spec 0006, mirrors the Claude Code adapter):
#   - Non-blocking / observe-only: always returns {"continue": true} and exits 0.
#   - Fail-open: any error (server down / timeout / missing deps) passes through.
#   - Spool-and-drain: a failed POST is queued locally; drain-failed.sh re-sends it.
#
# Cursor's beforeSubmitPrompt stdin JSON is RICHER than most: it includes user_email,
# conversation_id, generation_id, workspace_roots[], transcript_path, attachments[].
# Deps: jq, curl (if missing, pass through).
# =============================================================================

INPUT=$(cat)
allow() { printf '%s\n' '{"continue": true}'; }   # Cursor reads stdout JSON to decide continue

command -v jq   >/dev/null 2>&1 || { allow; exit 0; }
command -v curl >/dev/null 2>&1 || { allow; exit 0; }

# ---- config discovery: env wins → else a KEY=VALUE config file ----
SELF_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
_BUNDLED_ROOT=$(cd "$SELF_DIR/.." 2>/dev/null && pwd)
: "${PROMPT_AUDIT_DATA:=$HOME/.prompt-audit}"
_ENV_URL="$PROMPT_AUDIT_GATEWAY_URL"; _ENV_TOKEN="$PROMPT_AUDIT_GATEWAY_TOKEN"
EVENT_CWD=$(printf '%s' "$INPUT" | jq -r '.workspace_roots[0] // .cwd // empty')
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
case "$GATEWAY_URL" in *audit.example.com*) allow; exit 0 ;; esac   # unconfigured → no-op

# ---- extract fields from the Cursor beforeSubmitPrompt stdin JSON ----
PROMPT=$(printf '%s' "$INPUT" | jq -r '.prompt // empty')
[ -z "$PROMPT" ] && { allow; exit 0; }
SESSION=$(printf '%s' "$INPUT"    | jq -r '.conversation_id // empty')
GEN_ID=$(printf '%s' "$INPUT"     | jq -r '.generation_id // empty')
CWD=$(printf '%s' "$INPUT"        | jq -r '.workspace_roots[0] // empty')
TRANSCRIPT=$(printf '%s' "$INPUT" | jq -r '.transcript_path // empty')
EMAIL=$(printf '%s' "$INPUT"      | jq -r '.user_email // empty')   # Cursor provides this

TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
HOSTNAME=$(hostname 2>/dev/null || echo "unknown")

# ---- identity enrichment: payload → env → git → OS user (fail-open) ----
[ -z "$EMAIL" ] && EMAIL="${PROMPT_AUDIT_USER_EMAIL:-}"
USER_NAME="${PROMPT_AUDIT_USER_NAME:-}"
USER_UID="${PROMPT_AUDIT_USER_UID:-}"
ORG_ID="${PROMPT_AUDIT_ORG_ID:-}"
ORG_NAME="${PROMPT_AUDIT_ORG_NAME:-}"
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

# ---- idempotency event_id: prefer generation_id (stable per submission) ----
EVENT_ID="$GEN_ID"
if [ -z "$EVENT_ID" ]; then
  TS_MIN=$(printf '%s' "$TS" | cut -c1-16)
  _FP="${SESSION}|${TS_MIN}|${PROMPT}"
  if command -v shasum >/dev/null 2>&1; then EVENT_ID=$(printf '%s' "$_FP" | shasum -a 256 | cut -d' ' -f1)
  elif command -v sha256sum >/dev/null 2>&1; then EVENT_ID=$(printf '%s' "$_FP" | sha256sum | cut -d' ' -f1); fi
fi

PAYLOAD=$(jq -cn \
  --arg ts "$TS" --arg eid "$EVENT_ID" --arg sid "$SESSION" \
  --arg email "$EMAIL" --arg uname "$USER_NAME" --arg uuid_ "$USER_UID" \
  --arg org_id "$ORG_ID" --arg org_name "$ORG_NAME" \
  --arg repo "$REPO" --arg branch "$BRANCH" --arg cwd "$CWD" \
  --arg transcript "$TRANSCRIPT" --arg host "$HOSTNAME" --arg prompt "$PROMPT" \
  '{event_id:$eid,timestamp:$ts,session_id:$sid,user_email:$email,user_name:$uname,
    user_uid:$uuid_,org_id:$org_id,org_name:$org_name,repo:$repo,branch:$branch,
    cwd:$cwd,transcript_path:$transcript,hostname:$host,prompt:$prompt}')

FALLBACK_DIR="$PROMPT_AUDIT_DATA/failed"
if ! curl -sS -X POST "$GATEWAY_URL" \
      -H "Content-Type: application/json" -H "Authorization: Bearer $GATEWAY_TOKEN" \
      --max-time 2 --data-binary "$PAYLOAD" >/dev/null 2>&1; then
  mkdir -p "$FALLBACK_DIR" 2>/dev/null
  printf '%s\n' "$PAYLOAD" >> "$FALLBACK_DIR/pending-$(date +%Y-%m-%d).jsonl" 2>/dev/null
fi

allow          # observe-only: never block the submission
exit 0
