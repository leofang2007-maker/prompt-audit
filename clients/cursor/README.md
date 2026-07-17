# Cursor adapter

> ⚠️ **Verification status: built to the documented API — not yet verified on a live install**
> ([#16](https://github.com/leofang2007-maker/prompt-audit/issues/16)). Field names checked against
> Cursor's hooks docs and validated against a mock ingest server; not yet run on a real Cursor. If you
> run it for real and something's off, please
> [open an issue](https://github.com/leofang2007-maker/prompt-audit/issues/new). See
> [`clients/README.md`](../README.md).

Reports every prompt submitted in [Cursor](https://cursor.com) to a prompt-audit server via the
**`beforeSubmitPrompt`** hook (Cursor 1.7+). Non-blocking and fail-open — it always returns
`{"continue": true}` and never blocks a submission.

## What it captures

Cursor's `beforeSubmitPrompt` payload is rich — it even includes the user's email:

| ingest field | source |
|---|---|
| `prompt` | `.prompt` |
| `session_id` | `.conversation_id` |
| `event_id` | `.generation_id` → else `sha256(conversation_id \| minute \| prompt)` |
| `cwd` | `.workspace_roots[0]` |
| `transcript_path` | `.transcript_path` |
| `user_email` | `.user_email` (from Cursor) → else env → git → OS user |
| `user_name`, `org_*` | env `PROMPT_AUDIT_*` → git → OS user |
| `hostname` | `hostname` |
| `timestamp` | generated (`date -u`) |
| `repo`, `branch` | derived from the cwd's git |

Note: `beforeSubmitPrompt` is observe-and-gate only (it cannot rewrite the prompt); we only observe.

## Configuration

Same as the other adapters — env wins, else a `KEY=VALUE` file (`<cwd>/.prompt-audit.conf` →
`${PROMPT_AUDIT_DATA:-~/.prompt-audit}/config.conf` → `~/.prompt-audit.conf` → bundled `config.conf`):

```sh
PROMPT_AUDIT_GATEWAY_URL=https://audit.your-domain.com/api/v1/prompts
PROMPT_AUDIT_GATEWAY_TOKEN=iat_your_org_ingest_token
```

## Install — single user

Merge `settings/hooks.json` (replace `<ADAPTER_DIR>` with the absolute path here) into `~/.cursor/hooks.json`
(user) or `<project>/.cursor/hooks.json` (project). Set config, submit a prompt, check the audit UI.

## Install — enterprise (managed / team)

Precedence is **Enterprise → Team → Project → User**. Two admin paths:

- **Managed (MDM):** deploy the same `hooks.json` to the OS managed directory — macOS
  `/Library/Application Support/Cursor/hooks.json`, Linux `/etc/cursor/hooks.json`, Windows
  `C:\ProgramData\Cursor\hooks.json`. Also push the scripts + config.
- **Team (Enterprise plan):** configure the hook in the Cursor web dashboard; it auto-syncs to all team
  members.

> Enforcement caveat: Cursor's docs establish precedence + MDM deployment (a non-admin can't write the
> managed dir), but do **not** contain an explicit "users cannot disable this" guarantee — validate
> hands-on before making a hard compliance claim.

## Security

Only the write-only per-org ingest token lives on the machine; the hook is visible (not covert). Never
commit a real token to the bundled `config.conf`.

## Testing

```sh
echo '{"prompt":"hello audit","conversation_id":"c1","generation_id":"g1","workspace_roots":["'"$PWD"'"],"user_email":"dev@acme.com","hook_event_name":"beforeSubmitPrompt"}' \
  | PROMPT_AUDIT_GATEWAY_URL=https://audit.your-domain.com/api/v1/prompts \
    PROMPT_AUDIT_GATEWAY_TOKEN=iat_... sh scripts/report-prompt.sh
```

Prints `{"continue": true}` and always exits 0. On POST failure the payload is spooled to
`~/.prompt-audit/failed/` and re-sent by `drain-failed.sh` at the next session start.
