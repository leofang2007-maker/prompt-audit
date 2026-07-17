#!/bin/sh
# =============================================================================
# prompt-audit / Claude Code adapter — report-prompt.sh
# Event: UserPromptSubmit
# Purpose: forward each submitted prompt (+ enriched identity) to the audit server.
#
# Design principles (spec 0005):
#   - Non-blocking: always exits 0; never delays or rejects a prompt (pure audit).
#   - Fail-open: any error (server unreachable / timeout / missing deps) passes through.
#   - Identity enrichment: Claude Code's hook JSON carries NO user identity, so we fill it
#             from env → git config → OS user (all fail-open to null).
#   - Spool-and-drain: a failed POST is queued locally and re-sent by drain-failed.sh.
#
# Deps: jq, curl (usually pre-installed; if missing, pass through).
# =============================================================================

INPUT=$(cat)

# ---- dependency check: missing jq/curl → pass through ----
command -v jq   >/dev/null 2>&1 || exit 0
command -v curl >/dev/null 2>&1 || exit 0

# ---- config discovery: env wins → else a config file (KEY=VALUE) ----
# Deliver the real URL + token via MDM to the data path, or set env vars. NEVER commit a real token
# to the bundled config.conf (a token in a distributed package is a leaked token).
#
# Lookup order (first match wins, highest priority first):
#   1) project  <cwd>/.prompt-audit.conf                        — per-project, can travel w/ repo (no token in VCS)
#   2) data      ${PROMPT_AUDIT_DATA:-~/.prompt-audit}/config.conf — survives upgrades, best for MDM fleet rollout
#   3) user      ~/.prompt-audit.conf                            — single-machine fallback
#   4) bundled   <script_dir>/../config.conf                     — default shipped with the adapter (lowest priority)
SELF_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
_BUNDLED_ROOT=$(cd "$SELF_DIR/.." 2>/dev/null && pwd)
: "${PROMPT_AUDIT_DATA:=$HOME/.prompt-audit}"

_ENV_URL="$PROMPT_AUDIT_GATEWAY_URL"
_ENV_TOKEN="$PROMPT_AUDIT_GATEWAY_TOKEN"

EVENT_CWD=$(printf '%s' "$INPUT" | jq -r '.cwd // empty')
for _cfg in \
  "${EVENT_CWD:+$EVENT_CWD/.prompt-audit.conf}" \
  "$PROMPT_AUDIT_DATA/config.conf" \
  "$HOME/.prompt-audit.conf" \
  "${_BUNDLED_ROOT:+$_BUNDLED_ROOT/config.conf}"
do
  if [ -n "$_cfg" ] && [ -f "$_cfg" ]; then
    # shellcheck disable=SC1090
    . "$_cfg"
    break
  fi
done

# env wins: restore original env values over any the config file set.
[ -n "$_ENV_URL" ]   && PROMPT_AUDIT_GATEWAY_URL="$_ENV_URL"
[ -n "$_ENV_TOKEN" ] && PROMPT_AUDIT_GATEWAY_TOKEN="$_ENV_TOKEN"

GATEWAY_URL="${PROMPT_AUDIT_GATEWAY_URL:-https://audit.example.com/api/v1/prompts}"
GATEWAY_TOKEN="${PROMPT_AUDIT_GATEWAY_TOKEN:-}"

# Still the placeholder ⇒ this machine has no server configured: pass through silently.
case "$GATEWAY_URL" in
  *audit.example.com*) exit 0 ;;
esac

# ---- extract fields from the Claude Code UserPromptSubmit stdin JSON ----
PROMPT=$(printf '%s' "$INPUT"     | jq -r '.prompt // empty')
[ -z "$PROMPT" ] && exit 0        # don't collect empty prompts

SESSION=$(printf '%s' "$INPUT"    | jq -r '.session_id // empty')
CWD=$(printf '%s' "$INPUT"        | jq -r '.cwd // empty')
TRANSCRIPT=$(printf '%s' "$INPUT" | jq -r '.transcript_path // empty')
PROMPT_ID=$(printf '%s' "$INPUT"  | jq -r '.prompt_id // empty')

TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)     # Claude Code does NOT provide a timestamp — generate it
HOSTNAME=$(hostname 2>/dev/null || echo "unknown")

# ---- identity enrichment: env → git config → OS user (all fail-open to empty) ----
# Claude Code's hook JSON has no user/org identity; compliance attribution needs it.
EMAIL="${PROMPT_AUDIT_USER_EMAIL:-}"
USER_NAME="${PROMPT_AUDIT_USER_NAME:-}"
USER_UID="${PROMPT_AUDIT_USER_UID:-}"
ORG_ID="${PROMPT_AUDIT_ORG_ID:-}"
ORG_NAME="${PROMPT_AUDIT_ORG_NAME:-}"

if [ -n "$CWD" ] && command -v git >/dev/null 2>&1; then
  [ -z "$EMAIL" ]     && EMAIL=$(git -C "$CWD" config --get user.email 2>/dev/null)
  [ -z "$USER_NAME" ] && USER_NAME=$(git -C "$CWD" config --get user.name 2>/dev/null)
fi
[ -z "$USER_NAME" ] && USER_NAME=$(id -un 2>/dev/null || printf '%s' "${USER:-}")

# ---- repo/branch: derive from the cwd's git (read-only, empty on failure) ----
REPO=""
BRANCH=""
if [ -n "$CWD" ] && { [ -d "$CWD/.git" ] || [ -f "$CWD/.git" ]; } && command -v git >/dev/null 2>&1; then
  REPO=$(  git -C "$CWD" config --get remote.origin.url 2>/dev/null | sed 's#\.git$##;s#.*[/:]##')
  BRANCH=$(git -C "$CWD" symbolic-ref --short HEAD       2>/dev/null)
fi

# ---- idempotency event_id ----
# Prefer Claude Code's prompt_id (a stable UUID per submission; v2.1.196+). Fall back to a
# session+minute+prompt fingerprint so there's always a non-empty idempotency key. The server
# dedups on event_id, so re-sends (drain retries) never write duplicate rows.
EVENT_ID="$PROMPT_ID"
if [ -z "$EVENT_ID" ]; then
  TS_MIN=$(printf '%s' "$TS" | cut -c1-16)   # truncate to the minute
  _FP="${SESSION}|${TS_MIN}|${PROMPT}"
  if command -v shasum >/dev/null 2>&1; then
    EVENT_ID=$(printf '%s' "$_FP" | shasum -a 256 | cut -d' ' -f1)
  elif command -v sha256sum >/dev/null 2>&1; then
    EVENT_ID=$(printf '%s' "$_FP" | sha256sum | cut -d' ' -f1)
  fi
fi

# ---- assemble the audit record (jq handles escaping of newlines/quotes/special chars) ----
# -c compact single-line output: the local queue is one-record-per-line and drain-failed.sh
# re-sends line by line, so this MUST be single-line.
PAYLOAD=$(jq -cn \
  --arg ts "$TS" --arg eid "$EVENT_ID" --arg sid "$SESSION" \
  --arg email "$EMAIL" --arg uname "$USER_NAME" --arg uuid_ "$USER_UID" \
  --arg org_id "$ORG_ID" --arg org_name "$ORG_NAME" \
  --arg repo "$REPO" --arg branch "$BRANCH" --arg cwd "$CWD" \
  --arg transcript "$TRANSCRIPT" --arg host "$HOSTNAME" --arg prompt "$PROMPT" \
  '{
     event_id: $eid, timestamp: $ts, session_id: $sid,
     user_email: $email, user_name: $uname, user_uid: $uuid_,
     org_id: $org_id, org_name: $org_name,
     repo: $repo, branch: $branch, cwd: $cwd,
     transcript_path: $transcript, hostname: $host, prompt: $prompt
   }')

# ---- synchronous upload (short timeout), spool on failure ----
# Synchronous (not backgrounded): when the hook exits, Claude Code reaps the subprocess group, so a
# backgrounded curl would be killed. A round-trip is tens of ms; --max-time 2 caps the worst case
# well under the 30s UserPromptSubmit budget. Any failure queues locally and exits 0 (never blocks).
FALLBACK_DIR="$PROMPT_AUDIT_DATA/failed"
if ! curl -sS -X POST "$GATEWAY_URL" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $GATEWAY_TOKEN" \
      --max-time 2 \
      --data-binary "$PAYLOAD" \
      >/dev/null 2>&1; then
  mkdir -p "$FALLBACK_DIR" 2>/dev/null
  printf '%s\n' "$PAYLOAD" >> "$FALLBACK_DIR/pending-$(date +%Y-%m-%d).jsonl" 2>/dev/null
fi

# UserPromptSubmit can block the prompt, but auditing must NEVER block the user.
exit 0
