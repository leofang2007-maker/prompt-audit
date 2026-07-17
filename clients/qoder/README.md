# Prompt Audit — Qoder plugin (`prompt-audit`)

> ✅ **Verification status: verified in a real deployment** — exercised end-to-end against a live gateway
> on real dev machines. (See [`clients/README.md`](../README.md) for the status of all adapters.)

Enterprise audit plugin. Via Qoder's `UserPromptSubmit` hook, it captures every **raw prompt** a
team member submits to the Agent — plus identity/context (email / user / org / repo / branch /
session / transcript path) — and forwards it to an **audit gateway** (this project's server) for
your compliance/security team to review.

Core design: developer machines hold **no storage credentials**. The script only sends one
token-authenticated HTTPS request to the gateway; authentication and persistence are handled
entirely server-side.

> **Compliance note**: this plugin collects the full text of user prompts. Make sure that
> (1) affected team members are informed per your company policy; (2) the audit store is private,
> encrypted at rest, access-controlled, and has a defined retention period; (3) prompt text may
> contain secrets, internal IPs, or customer data — the audit log itself is a sensitive data asset
> and must be protected accordingly.

## Components

| Component | Description |
|-----------|-------------|
| Hook: `UserPromptSubmit` | Fires when a prompt is submitted, before the Agent processes it. Reports the prompt; never blocks the user. |
| Hook: `SessionStart` | Drains any locally-queued reports that failed earlier (gateway was briefly unreachable). |
| Command: `/audit-check` | Checks whether the gateway is configured + reachable. |
| Skill: `audit-status` | Explains what the plugin does and how to verify it's active. |

## How it works

```
user submits a prompt
   └─> UserPromptSubmit fires upload-prompt.sh
          ├─ extract prompt + identity fields from stdin JSON
          ├─ jq assembles the audit record (escapes newlines/quotes/etc.)
          ├─ POST to the audit gateway (--max-time 2)
          │     └─ on failure, append to a local queue: failed/pending-<date>.jsonl
          └─ exit 0 immediately — the Agent is never delayed

next session start
   └─> SessionStart fires drain-failed.sh → re-POST queued records (idempotent via event_id)
```

Design principles:
- **Never blocks**: `UserPromptSubmit` can block a prompt, but for auditing we always `exit 0`.
- **Fail-open**: missing `jq`/`curl`, gateway unreachable, timeout — any error passes the prompt through.
- **No credentials on the machine**: no storage AK/SK locally; the bearer token goes to the gateway
  over HTTPS, and auth/storage stay server-side.
- **Idempotent**: each record carries an `event_id` (the IDE's stable `request_set_id`, or a
  `session|minute|prompt` hash fallback) so the gateway dedups double-fires and drain retries.

## Gateway contract

The plugin sends a `POST` to `QODER_AUDIT_GATEWAY_URL` with `Content-Type: application/json` and
`Authorization: Bearer <token>`. Body is a single audit record:

```json
{
  "event_id":        "833017a9-39eb-4c85-8220-2d4110fb3576",
  "timestamp":       "2026-07-15T10:23:33Z",
  "session_id":      "sess-123",
  "user_email":      "dev@corp.com",
  "user_name":       "dev",
  "user_uid":        "…",
  "org_id":          "…",
  "org_name":        "…",
  "repo":            "team/app",
  "branch":          "main",
  "cwd":             "/proj",
  "transcript_path": "/Users/dev/.qoder/…/task.jsonl",
  "hostname":        "dev-macbook.local",
  "prompt":          "the user's full raw prompt"
}
```

The server (this project) validates the token, stamps the trusted owning org from the token, dedups
on `event_id`, stores the record, and returns `200 {"ok":true,"id":"…"}`. Only `prompt` is required;
everything else is optional and stored as-is (fail-open).

## Configuration

The gateway URL and token are delivered via a **config file** (recommended), or overridden by
environment variables. Config files are simple `KEY=VALUE`, one per line:

```sh
QODER_AUDIT_GATEWAY_URL=https://audit.example.com/api/v1/prompts
QODER_AUDIT_GATEWAY_TOKEN=<ingest-token>
```

Lookup order (first match wins):

| Priority | Path | Use |
|----------|------|-----|
| 1 | `<project>/.qoder/prompt-audit.conf` | per-project gateway; can travel with the repo (**never commit the token**) |
| 2 | `${QODER_PLUGIN_DATA}/config.conf` | plugin data dir, survives upgrades — best for **MDM fleet rollout** |
| 3 | `~/.qoder/prompt-audit.conf` | per-user fallback |
| 4 | `${QODER_PLUGIN_ROOT}/config.conf` | bundled default (lowest priority; ships empty/unconfigured) |

Environment variables `QODER_AUDIT_GATEWAY_URL` / `QODER_AUDIT_GATEWAY_TOKEN`, if set, **override**
the file.

> **For IDE use, deliver via a config file.** GUI apps launched from the Dock/Finder don't inherit
> a login shell's `export`, so env vars are only reliable from a CLI. For fleet-wide enforcement,
> use MDM (Jamf/Intune/…) to push the config file to path 2.

When no gateway is configured, the plugin fails open (passes through) and queues records locally at
`${QODER_PLUGIN_DATA}/failed/pending-<date>.jsonl`.

## Install

**Qoder IDE / Quest**: Settings → Plugins → Import, and pick this plugin folder. Or in the
Marketplace, **+ Create Plugin → import from local folder**.

**Qoder CLI**:

```bash
# team-wide enforcement: install at project scope and commit to Git
qodercli plugins install /path/to/prompt-audit --scope project
# then restart the CLI or run /plugins reload
```

Installed at project scope and committed, every team member gets it on pull; the hook shows as
"From Plugin" and members can't individually disable or delete it — satisfying mandatory collection.

## Layout

```
prompt-audit/
├── .qoder-plugin/plugin.json    # plugin manifest
├── hooks/hooks.json             # registers UserPromptSubmit + SessionStart hooks
├── scripts/
│   ├── upload-prompt.sh         # capture + POST (UserPromptSubmit)
│   └── drain-failed.sh          # retry queued records (SessionStart)
├── commands/audit-check.md      # /audit-check command
├── skills/audit-status/SKILL.md # status skill
└── config.conf                  # bundled default config (ships unconfigured)
```

## Dependencies

- `jq` (parse/assemble JSON)
- `curl` (POST to the gateway)

Usually pre-installed on standard developer machines; if missing, the script silently passes through
— no error, no blocking.

## Local test

```bash
jq -n '{prompt:"test prompt", session_id:"s1", extra:{user:{email:"a@b.com"}}}' \
  | QODER_AUDIT_GATEWAY_URL=https://your-gateway/api/v1/prompts \
    QODER_AUDIT_GATEWAY_TOKEN=xxx \
    ./scripts/upload-prompt.sh
echo "exit: $?"   # expect 0
```
