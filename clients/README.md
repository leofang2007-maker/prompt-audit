# Client adapters

Each adapter forwards prompts from an AI coding tool to the prompt-audit server via that tool's
prompt-submit hook, using the same ingest contract (`POST /api/v1/prompts` with a per-org ingest token).

## Verification status

| Adapter | Tool | Status | Notes |
|---|---|---|---|
| [`qoder/`](qoder/) | Qoder | ✅ **Verified in a real deployment** | Exercised end-to-end against a live gateway on real dev machines. |
| [`claude-code/`](claude-code/) | Claude Code | ⚠️ **Doc-based — not yet live-verified** ([#15](https://github.com/leofang2007-maker/prompt-audit/issues/15)) | `UserPromptSubmit` managed hook (enterprise-enforced). Validated vs a mock; not yet run on a real install. |
| [`cursor/`](cursor/) | Cursor | ⚠️ **Doc-based — not yet live-verified** ([#16](https://github.com/leofang2007-maker/prompt-audit/issues/16)) | `beforeSubmitPrompt` hook (Cursor 1.7+); rich payload incl. `user_email`. |
| [`codex/`](codex/) | Codex CLI | ⚠️ **Doc-based — not yet live-verified** ([#17](https://github.com/leofang2007-maker/prompt-audit/issues/17)) | `UserPromptSubmit` hook (experimental). **CLI only** — the IDE extension has no hooks. |
| [`copilot/`](copilot/) | GitHub Copilot | ⚠️ **Doc-based + partial coverage** ([#18](https://github.com/leofang2007-maker/prompt-audit/issues/18)) | `UserPromptSubmit` hook on **agent surfaces only** (CLI / cloud agent / VS Code agent-mode). Classic chat / completions / JetBrains are **not capturable** — see its README. |

> **What "not yet verified" means:** the Claude Code adapter (and any future adapter marked this way) is
> written to the tool's **documented** hook contract and validated against a mock server, but has **not**
> been run end-to-end on a real install of that tool. The hook JSON shape, managed-settings enforcement,
> and timeouts are as documented — but docs and reality occasionally differ across versions.
>
> **If you hit a problem** running one of these on a real install, please
> [open an issue](https://github.com/leofang2007-maker/prompt-audit/issues/new) with the tool version
> and what you saw — that's the fastest path to a fix, and it lets us promote the adapter to "verified."

Adapters are non-blocking and fail-open by design: a misbehaving hook passes the prompt through rather
than disrupting the developer.
