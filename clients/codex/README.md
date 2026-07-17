# Codex adapter

> ⚠️ **Verification status: built to the documented API — not yet verified on a live install**
> ([#17](https://github.com/leofang2007-maker/prompt-audit/issues/17)). Codex hooks are an
> **experimental** engine — verify the exact hook schema against the [openai/codex](https://github.com/openai/codex)
> repo (draft-07 hook schemas) before rollout. Validated against a mock ingest server. See
> [`clients/README.md`](../README.md).

Reports each prompt submitted in **Codex CLI** to a prompt-audit server via the **`UserPromptSubmit`**
hook. Non-blocking, fail-open.

## Coverage

- ✅ **Codex CLI** — the `UserPromptSubmit` hook fires at submit time with the prompt text.
- ⛔ **Codex IDE extension (VS Code)** — has **no hook support**; the only capture there is the session
  rollout logs (`~/.codex/sessions/**/rollout-*.jsonl`), which this adapter does **not** tail (out of
  scope). Coverage (#4) will show IDE-only hosts as never-reporting against a roster.

## What it captures

| ingest field | source |
|---|---|
| `prompt` | `.prompt` |
| `session_id` | `.session_id` |
| `event_id` | `sha256(session_id \| minute \| prompt)` (no per-submit id in the payload) |
| `cwd` | `.cwd` |
| `transcript_path` | `.transcript_path` |
| `user_email`, `user_name`, `org_*` | env `PROMPT_AUDIT_*` → git → OS user (not in the payload) |
| `hostname` | `hostname` |
| `timestamp` | generated (`date -u`) |

## Configuration

Env wins, else a `KEY=VALUE` file (`<cwd>/.prompt-audit.conf` → `~/.prompt-audit/config.conf` →
`~/.prompt-audit.conf` → bundled). Set `PROMPT_AUDIT_GATEWAY_URL` + `PROMPT_AUDIT_GATEWAY_TOKEN`.

## Install — single user

Merge `settings/config.toml` (replace `<ADAPTER_DIR>`) into `~/.codex/config.toml` (user) or
`<repo>/.codex/config.toml` (project). A JSON form (`~/.codex/hooks.json`) also works.

## Install — enterprise (enforced, users cannot remove)

Deploy `settings/requirements.toml` via MDM (replace `<MANAGED_DIR>` with where the scripts are
installed). `allow_managed_hooks_only = true` (only honored in `requirements.toml`) makes Codex skip all
user/project hooks — so the audit hook is loaded from the managed layer and cannot be disabled.

## Security

Only the write-only per-org ingest token lives on the machine; the hook is visible. Never commit a real
token to the bundled `config.conf`.

## Testing

```sh
echo '{"prompt":"hello audit","session_id":"s1","cwd":"'"$PWD"'","model":"gpt-x","hook_event_name":"UserPromptSubmit"}' \
  | PROMPT_AUDIT_GATEWAY_URL=https://audit.your-domain.com/api/v1/prompts \
    PROMPT_AUDIT_GATEWAY_TOKEN=iat_... sh scripts/report-prompt.sh
```

Always exits 0. On POST failure the payload is spooled and re-sent by `drain-failed.sh`.
