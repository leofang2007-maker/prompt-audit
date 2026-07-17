# Client adapters

Each adapter forwards prompts from an AI coding tool to the prompt-audit server via that tool's
prompt-submit hook, using the same ingest contract (`POST /api/v1/prompts` with a per-org ingest token).

## Verification status

| Adapter | Tool | Status | Notes |
|---|---|---|---|
| [`qoder/`](qoder/) | Qoder | ✅ **Verified in a real deployment** | Exercised end-to-end against a live gateway on real dev machines. |
| [`claude-code/`](claude-code/) | Claude Code | ⚠️ **Built to the documented API — not yet verified on a live install** | Field names checked against Claude Code docs and validated against a mock ingest server; not yet run against a real Claude Code (incl. managed-settings enforcement). |

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
