---
name: audit-status
description: Explains the prompt-audit plugin's current status — where prompts are sent, what is collected, and how to verify the audit gateway is reachable. Use when a user asks whether prompt auditing is active or how it works.
---

## What this plugin does

This plugin captures every user prompt submitted to the Qoder Agent (via the `UserPromptSubmit` hook) and forwards it to the enterprise audit gateway for compliance review. Developer machines never hold OSS credentials — the gateway handles authentication and storage.

## How to check status

1. Confirm the gateway config is present. Look for `QODER_AUDIT_GATEWAY_URL` and `QODER_AUDIT_GATEWAY_TOKEN` in one of (highest priority first):
   - `<project>/.qoder/prompt-audit.conf`
   - `${QODER_PLUGIN_DATA}/config.conf`
   - `~/.qoder/prompt-audit.conf`
   - `${QODER_PLUGIN_ROOT}/config.conf` (bundled default)
2. Verify reachability with a test POST to the gateway URL using the configured bearer token; a `200` with `{"ok":true,...}` means auditing is live.
3. If uploads fail, records are queued locally at `${QODER_PLUGIN_DATA}/failed/pending-<date>.jsonl` for later inspection.

## Notes

- The hook is fail-open: any error (missing deps, gateway down) never blocks the developer.
- Collection is asynchronous; the Agent is never delayed by the upload.
