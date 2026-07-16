#!/bin/sh
# =============================================================================
# prompt-audit / upload-prompt.sh
# Event: UserPromptSubmit
# Purpose: forward each submitted raw prompt + identity to the audit gateway.
#
# Design principles:
#   - Non-blocking: returns fast (exit 0); never delays the Agent.
#   - Fail-open: any error (gateway unreachable / timeout / missing deps) passes through.
#   - No credentials: the machine holds no storage AK/SK; it sends one token-authenticated
#             HTTPS request to the gateway, which handles auth + storage server-side.
#
# Deps: jq, curl (usually pre-installed on standard dev machines; if missing, pass through).
# =============================================================================

# ---- read the event input (stdin JSON) ----
INPUT=$(cat)

# ---- derive the plugin root from the script's own location (important) ----
# The IDE expands ${QODER_PLUGIN_ROOT}/${QODER_PLUGIN_DATA} into the hooks.json command string
# (so $0 is an absolute path), but does NOT export those two vars into the hook subprocess
# environment. So at startup they'd be empty, the ${VAR:+...} entries in the config lookup expand
# to empty, paths 2/4 get silently dropped, a fresh machine has no path 1/3 → falls back to the
# placeholder gateway → DNS failure → everything queued. Deriving from $0 makes config discovery
# independent of how we're invoked, deterministically hitting the bundled config.conf.
SELF_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
_PLUGIN_ROOT_FALLBACK=$(cd "$SELF_DIR/.." 2>/dev/null && pwd)   # scripts/.. = plugin root
: "${QODER_PLUGIN_ROOT:=$_PLUGIN_ROOT_FALLBACK}"
: "${QODER_PLUGIN_DATA:=$HOME/.qoder/prompt-audit}"

# ---- dependency check: missing jq/curl → pass through ----
command -v jq   >/dev/null 2>&1 || exit 0
command -v curl >/dev/null 2>&1 || exit 0

# ---- gather config: environment variables win → else config file ----
# Config files are simple KEY=VALUE (one per line), e.g.:
#   QODER_AUDIT_GATEWAY_URL=https://audit-gateway.internal.example.com/v1/prompt
#   QODER_AUDIT_GATEWAY_TOKEN=xxxxxx
# Push via MDM (Jamf/Intune/…) to any of the paths below.
# GUI-launched IDEs can't read a shell's export, so config files are the primary delivery for IDEs.
#
# Lookup order (first match wins, highest priority first):
#   1) project  <cwd>/.qoder/prompt-audit.conf     — per-project gateway, can travel w/ repo (no token in VCS)
#   2) data      ${QODER_PLUGIN_DATA}/config.conf    — survives upgrades, best for MDM fleet rollout
#   3) user      ~/.qoder/prompt-audit.conf          — single-machine fallback
#   4) bundled   ${QODER_PLUGIN_ROOT}/config.conf     — default shipped with the plugin (lowest priority)
#      In production this file should carry NO token (placeholder only); deliver the real one via MDM to path 2.
# Save the original env values first (sourcing a config file overrides same-named vars; we restore
# them afterwards so env keeps priority).
_ENV_URL="$QODER_AUDIT_GATEWAY_URL"
_ENV_TOKEN="$QODER_AUDIT_GATEWAY_TOKEN"

EVENT_CWD=$(printf '%s' "$INPUT" | jq -r '.cwd // empty')
for _cfg in \
  "${EVENT_CWD:+$EVENT_CWD/.qoder/prompt-audit.conf}" \
  "${QODER_PLUGIN_DATA:+$QODER_PLUGIN_DATA/config.conf}" \
  "$HOME/.qoder/prompt-audit.conf" \
  "${QODER_PLUGIN_ROOT:+$QODER_PLUGIN_ROOT/config.conf}"
do
  if [ -n "$_cfg" ] && [ -f "$_cfg" ]; then
    # shellcheck disable=SC1090
    . "$_cfg"
    break
  fi
done

# env wins: if the vars were originally set, restore them over the file's values.
[ -n "$_ENV_URL" ]   && QODER_AUDIT_GATEWAY_URL="$_ENV_URL"
[ -n "$_ENV_TOKEN" ] && QODER_AUDIT_GATEWAY_TOKEN="$_ENV_TOKEN"

# Both empty → use the placeholder default (must be replaced post-install or delivered via config).
GATEWAY_URL="${QODER_AUDIT_GATEWAY_URL:-https://audit-gateway.internal.example.com/v1/prompt}"
GATEWAY_TOKEN="${QODER_AUDIT_GATEWAY_TOKEN:-}"

# If it's still the placeholder after all fallbacks, this machine has no gateway configured:
# pass through silently, avoiding a doomed DNS lookup + pointless local-queue noise.
case "$GATEWAY_URL" in
  *internal.example.com*) exit 0 ;;
esac

# ---- extract fields ----
PROMPT=$(printf '%s' "$INPUT"  | jq -r '.prompt // empty')
[ -z "$PROMPT" ] && exit 0   # don't collect empty prompts

SESSION=$(printf '%s' "$INPUT" | jq -r '.session_id // empty')
CWD=$(printf '%s' "$INPUT"     | jq -r '.cwd // empty')
TRANSCRIPT=$(printf '%s' "$INPUT" | jq -r '.transcript_path // empty')

# ---- user identity: the real stdin puts it under .extra.user (not .extra) ----
# Pull the user object once to avoid forking jq repeatedly.
USER_JSON=$(printf '%s' "$INPUT" | jq -c '.extra.user // {}')
EMAIL=$(    printf '%s' "$USER_JSON" | jq -r '.email    // empty')
USER_NAME=$(printf '%s' "$USER_JSON" | jq -r '.name     // empty')
USER_UID=$( printf '%s' "$USER_JSON" | jq -r '.uid      // empty')
ORG_ID=$(   printf '%s' "$USER_JSON" | jq -r '.org_id   // empty')
ORG_NAME=$( printf '%s' "$USER_JSON" | jq -r '.org_name // empty')

# ---- repo/branch: not in the IDE stdin; derive from the cwd's git (read-only, empty on failure) ----
REPO=""
BRANCH=""
if [ -n "$CWD" ] && { [ -d "$CWD/.git" ] || [ -f "$CWD/.git" ]; } && command -v git >/dev/null 2>&1; then
  REPO=$(  git -C "$CWD" config --get remote.origin.url 2>/dev/null | sed 's#\.git$##;s#.*[/:]##')
  BRANCH=$(git -C "$CWD" symbolic-ref --short HEAD       2>/dev/null)
fi

TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
HOSTNAME=$(hostname 2>/dev/null || echo "unknown")

# ---- idempotency event_id ----
# Prefer the IDE-provided request_set_id (a stable UUID for one submission).
# Qoder has a bug where a hook is registered twice, so one submission fires multiple times; but
# request_set_id stays constant across those fires, so using it as event_id → the gateway dedups
# and writes exactly one row no matter how many times it fires — and it also avoids the
# cross-minute-boundary miss that a minute-level fingerprint can have.
# Tolerate different field names/locations: top-level .request_set_id / camelCase .requestSetId /
# nested .extra.request_set_id.
EVENT_ID=$(printf '%s' "$INPUT" | jq -r '
  .request_set_id // .requestSetId // .extra.request_set_id // empty')

# Fallback: if an event carries no request_set_id, fall back to a session+prompt+minute fingerprint,
# so there's always a (non-empty) idempotency key.
if [ -z "$EVENT_ID" ]; then
  TS_MIN=$(printf '%s' "$TS" | cut -c1-16)   # truncate to the minute: 2026-07-15T08:57
  _FP="${SESSION}|${TS_MIN}|${PROMPT}"
  if command -v shasum >/dev/null 2>&1; then
    EVENT_ID=$(printf '%s' "$_FP" | shasum -a 256 | cut -d' ' -f1)
  elif command -v sha256sum >/dev/null 2>&1; then
    EVENT_ID=$(printf '%s' "$_FP" | sha256sum | cut -d' ' -f1)
  else
    EVENT_ID=""
  fi
fi

# ---- assemble the audit record (JSON) ----
# Build with jq, which handles escaping of newlines/quotes/special chars natively.
# -c compact single-line output: the local queue pending-*.jsonl relies on "one record = one line",
# and drain-failed.sh re-sends line by line, so this MUST be single-line (not jq's default pretty).
PAYLOAD=$(jq -cn \
  --arg ts         "$TS" \
  --arg eid        "$EVENT_ID" \
  --arg sid        "$SESSION" \
  --arg email      "$EMAIL" \
  --arg uname      "$USER_NAME" \
  --arg uuid_      "$USER_UID" \
  --arg org_id     "$ORG_ID" \
  --arg org_name   "$ORG_NAME" \
  --arg repo       "$REPO" \
  --arg branch     "$BRANCH" \
  --arg cwd        "$CWD" \
  --arg transcript "$TRANSCRIPT" \
  --arg host       "$HOSTNAME" \
  --arg prompt     "$PROMPT" \
  '{
     event_id:        $eid,
     timestamp:       $ts,
     session_id:      $sid,
     user_email:      $email,
     user_name:       $uname,
     user_uid:        $uuid_,
     org_id:          $org_id,
     org_name:        $org_name,
     repo:            $repo,
     branch:          $branch,
     cwd:             $cwd,
     transcript_path: $transcript,
     hostname:        $host,
     prompt:          $prompt
   }')

# ---- synchronous upload (short timeout) ----
# Why synchronous rather than a background job:
#   When the hook script exits, the IDE SIGKILLs the whole subprocess group — nohup/disown can't
#   stop it (nohup only ignores SIGHUP; disown just drops it from the shell job table; macOS has no
#   setsid by default to start a new session/group). In testing, a backgrounded curl is killed 100%
#   of the time and every record lands in failed/. A gateway round-trip is ~76ms, so a synchronous
#   upload is almost imperceptible while completely eliminating the process-lifecycle problem.
# Fail-open: any non-zero curl exit (network/timeout/process-level error) queues locally and exits 0,
# never blocking the user. The gateway handles auth + storage and can dedup by event_id.
FALLBACK_DIR="${QODER_PLUGIN_DATA:-$HOME/.qoder/prompt-audit}/failed"

# --max-time 2: the gateway is normally <100ms; cap at 2s so even the worst case is well under the
# hook budget. A timeout is treated as a failure and queued, so prompt submission never hangs.
if ! curl -sS -X POST "$GATEWAY_URL" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $GATEWAY_TOKEN" \
      --max-time 2 \
      --data-binary "$PAYLOAD" \
      >/dev/null 2>&1; then
  mkdir -p "$FALLBACK_DIR" 2>/dev/null
  printf '%s\n' "$PAYLOAD" >> "$FALLBACK_DIR/pending-$(date +%Y-%m-%d).jsonl" 2>/dev/null
fi

# ---- pass through: UserPromptSubmit can block, but auditing must never block the user ----
exit 0
