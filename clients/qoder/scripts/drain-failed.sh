#!/bin/sh
# =============================================================================
# prompt-audit / drain-failed.sh
# Event: SessionStart (one drain pass per session start)
# Purpose: re-deliver records that were queued locally when the gateway was briefly unreachable.
#
# Design principles:
#   - Idempotent: every record carries an event_id; the gateway dedups, so re-sending is safe.
#   - Fail-open: any error exits silently; never blocks session start.
#   - Delete on success: re-send line by line; drop successful lines, keep failed ones for next time.
#   - Rotate when drained: when a whole file is fully sent, rename it with a .done suffix so it isn't rescanned.
#
# Deps: curl (exit if missing; jq is not strictly required).
# =============================================================================

command -v curl >/dev/null 2>&1 || exit 0

# ---- derive the plugin root from the script's own location (same fix as upload-prompt.sh) ----
# The IDE doesn't export ${QODER_PLUGIN_ROOT}/${QODER_PLUGIN_DATA} into the hook subprocess env,
# so derive from $0 to ensure SessionStart drain can also find the bundled config.conf + queue dir.
SELF_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
_PLUGIN_ROOT_FALLBACK=$(cd "$SELF_DIR/.." 2>/dev/null && pwd)
: "${QODER_PLUGIN_ROOT:=$_PLUGIN_ROOT_FALLBACK}"
: "${QODER_PLUGIN_DATA:=$HOME/.qoder/prompt-audit}"

# ---- read the event input, reuse the same config lookup as upload ----
INPUT=$(cat 2>/dev/null)

_ENV_URL="$QODER_AUDIT_GATEWAY_URL"
_ENV_TOKEN="$QODER_AUDIT_GATEWAY_TOKEN"

EVENT_CWD=""
if command -v jq >/dev/null 2>&1 && [ -n "$INPUT" ]; then
  EVENT_CWD=$(printf '%s' "$INPUT" | jq -r '.cwd // empty' 2>/dev/null)
fi

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

[ -n "$_ENV_URL" ]   && QODER_AUDIT_GATEWAY_URL="$_ENV_URL"
[ -n "$_ENV_TOKEN" ] && QODER_AUDIT_GATEWAY_TOKEN="$_ENV_TOKEN"

GATEWAY_URL="${QODER_AUDIT_GATEWAY_URL:-https://audit-gateway.internal.example.com/v1/prompt}"
GATEWAY_TOKEN="${QODER_AUDIT_GATEWAY_TOKEN:-}"

# No real gateway configured (still the placeholder) → don't drain, to avoid doomed retries.
case "$GATEWAY_URL" in
  *internal.example.com*) exit 0 ;;
esac

FALLBACK_DIR="${QODER_PLUGIN_DATA:-$HOME/.qoder/prompt-audit}/failed"
[ -d "$FALLBACK_DIR" ] || exit 0

# ---- drain each file ----
for f in "$FALLBACK_DIR"/pending-*.jsonl; do
  [ -f "$f" ] || continue          # when no file matches, the glob returns itself — skip it
  tmp="$f.tmp.$$"
  : > "$tmp" 2>/dev/null || continue
  all_ok=1

  while IFS= read -r line; do
    [ -z "$line" ] && continue
    if curl -sS -X POST "$GATEWAY_URL" \
          -H "Content-Type: application/json" \
          -H "Authorization: Bearer $GATEWAY_TOKEN" \
          --max-time 2 \
          --data-binary "$line" \
          >/dev/null 2>&1; then
      :                             # success: don't write back to tmp, i.e. drop the line
    else
      printf '%s\n' "$line" >> "$tmp"   # failure: keep for next time
      all_ok=0
    fi
  done < "$f"

  if [ "$all_ok" -eq 1 ]; then
    rm -f "$tmp" 2>/dev/null
    mv "$f" "$f.done" 2>/dev/null    # fully drained: rotate out of scan range
  else
    mv "$tmp" "$f" 2>/dev/null       # still has failures: overwrite original with remaining lines
  fi
done

exit 0
