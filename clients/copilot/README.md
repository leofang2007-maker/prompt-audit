# GitHub Copilot adapter

> ⚠️ **Verification status: built to the documented API — not yet verified on a live install**
> ([#18](https://github.com/leofang2007-maker/prompt-audit/issues/18)). Copilot hooks / VS Code agent
> hooks are **Preview** — verify the exact config format + location against
> [GitHub's hooks reference](https://docs.github.com/en/copilot/reference/hooks-reference) before
> rollout. Validated against a mock ingest server. See [`clients/README.md`](../README.md).

Reports each prompt submitted to **GitHub Copilot's agent surfaces** via the **`UserPromptSubmit`** hook.
Non-blocking, fail-open.

## ⚠️ Surface coverage — read this first

GitHub only exposes a prompt-submit hook on the **agent** surfaces. This adapter captures:

| Surface | Captured? |
|---|---|
| **Copilot CLI** (`copilot` / `gh copilot`) | ✅ yes (`UserPromptSubmit` hook) |
| **Copilot cloud coding agent** | ✅ yes |
| **VS Code — agent mode** (Preview) | ✅ yes |
| **VS Code — classic ask/edit chat** | ⛔ **no hook exists** |
| **Inline completions** | ⛔ no |
| **JetBrains Copilot** | ⛔ no supported hook |

For the ⛔ surfaces there is **no supported client-side capture** — third-party VS Code extensions are
blocked by API isolation, and the GitHub admin audit log contains **metadata only, no prompt text**.
This adapter does not fake that coverage. If your users are mostly in classic Chat or JetBrains, this
adapter will not see their prompts — plan accordingly (and coverage #4 will flag those hosts).

## What it captures (on the supported surfaces)

| ingest field | source |
|---|---|
| `prompt` | `.prompt` |
| `session_id` | `.sessionId` |
| `event_id` | `sha256(sessionId \| timestamp \| prompt)` |
| `cwd` | `.cwd` |
| `timestamp` | `.timestamp` (else generated) |
| `user_email`, `user_name`, `org_*` | env `PROMPT_AUDIT_*` → git → OS user (not in the payload) |
| `hostname` | `hostname` |

## Configuration

Env wins, else a `KEY=VALUE` file (`<cwd>/.prompt-audit.conf` → `~/.prompt-audit/config.conf` →
`~/.prompt-audit.conf` → bundled). Set `PROMPT_AUDIT_GATEWAY_URL` + `PROMPT_AUDIT_GATEWAY_TOKEN`.

## Install

Register `settings/hooks.json` (replace `<ADAPTER_DIR>`) with Copilot's hook config for your surface
(CLI / agent). For enterprise, deploy it as a machine-wide **policy hook** (admin-installed) — policy
hooks load before all others and **cannot be disabled** by users. Verify the exact config path/format
against GitHub's hooks reference (Preview).

## Alternatives for the uncovered surfaces

- **Copilot CLI OTel content capture** — set `OTEL_EXPORTER_OTLP_ENDPOINT` +
  `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=true` to emit prompt/response as OTel spans (CLI
  only; not wired to this adapter).
- **Admin audit log API** — metadata only, no prompt text.
- A network proxy in the Copilot traffic path — unsupported/brittle; out of scope.

## Security

Only the write-only per-org ingest token lives on the machine; the hook is visible. Never commit a real
token to the bundled `config.conf`.

## Testing

```sh
echo '{"prompt":"hello audit","sessionId":"s1","cwd":"'"$PWD"'","timestamp":"2026-07-17T00:00:00Z"}' \
  | PROMPT_AUDIT_GATEWAY_URL=https://audit.your-domain.com/api/v1/prompts \
    PROMPT_AUDIT_GATEWAY_TOKEN=iat_... sh scripts/report-prompt.sh
```

Always exits 0. On POST failure the payload is spooled and re-sent by `drain-failed.sh`.
