#!/bin/sh
# =============================================================================
# prompt-audit / Claude Code adapter — drain-failed.sh
# Event: SessionStart (one drain pass per session start)
# Purpose: re-deliver records queued locally while the server was briefly unreachable.
#
# Design principles (spec 0005):
#   - Idempotent: every record carries an event_id; the server dedups, so re-sending is safe.
#   - Fail-open: any error exits silently; never blocks session start.
#   - Delete on success, keep on failure; rotate a fully-drained file to .done.
#
# Deps: curl (exit if missing).
# =============================================================================

command -v curl >/dev/null 2>&1 || exit 0

SELF_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
_BUNDLED_ROOT=$(cd "$SELF_DIR/.." 2>/dev/null && pwd)
: "${PROMPT_AUDIT_DATA:=$HOME/.prompt-audit}"

INPUT=$(cat 2>/dev/null)

_ENV_URL="$PROMPT_AUDIT_GATEWAY_URL"
_ENV_TOKEN="$PROMPT_AUDIT_GATEWAY_TOKEN"

EVENT_CWD=""
if command -v jq >/dev/null 2>&1 && [ -n "$INPUT" ]; then
  EVENT_CWD=$(printf '%s' "$INPUT" | jq -r '.cwd // empty' 2>/dev/null)
fi

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

[ -n "$_ENV_URL" ]   && PROMPT_AUDIT_GATEWAY_URL="$_ENV_URL"
[ -n "$_ENV_TOKEN" ] && PROMPT_AUDIT_GATEWAY_TOKEN="$_ENV_TOKEN"

GATEWAY_URL="${PROMPT_AUDIT_GATEWAY_URL:-https://audit.example.com/api/v1/prompts}"
GATEWAY_TOKEN="${PROMPT_AUDIT_GATEWAY_TOKEN:-}"

case "$GATEWAY_URL" in
  *audit.example.com*) exit 0 ;;      # no real server configured → don't retry
esac

FALLBACK_DIR="$PROMPT_AUDIT_DATA/failed"
[ -d "$FALLBACK_DIR" ] || exit 0

for f in "$FALLBACK_DIR"/pending-*.jsonl; do
  [ -f "$f" ] || continue           # unmatched glob returns itself — skip
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
      :                             # success: drop the line
    else
      printf '%s\n' "$line" >> "$tmp"   # failure: keep for next time
      all_ok=0
    fi
  done < "$f"

  if [ "$all_ok" -eq 1 ]; then
    rm -f "$tmp" 2>/dev/null
    mv "$f" "$f.done" 2>/dev/null     # fully drained: rotate out of scan range
  else
    mv "$tmp" "$f" 2>/dev/null        # keep the remaining failures
  fi
done

exit 0
