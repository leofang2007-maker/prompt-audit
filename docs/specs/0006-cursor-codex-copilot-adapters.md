# 0006 — Cursor / Codex / GitHub Copilot adapters

- **Status:** Implemented (⚠️ all three doc-based — not yet verified on live installs; see [`clients/README.md`](../../clients/README.md) and issues #16/#17/#18)
- **Issue:** [#5](https://github.com/leofang2007-maker/prompt-audit/issues/5)
- **Author:** —
- **Created:** 2026-07-17

## Problem & motivation

To be a credible tool-agnostic compliance layer, the audit server needs adapters for the tools teams
actually run. [Spec 0005](0005-claude-code-adapter.md) shipped Claude Code; this spec adds **Cursor**,
**OpenAI Codex**, and **GitHub Copilot** — all as the same non-blocking, fail-open, spool-and-drain
`UserPromptSubmit`-style command-hook adapter, reusing the ingest contract (no server change).

## Feasibility (verified against official docs, 2026-07-17)

| Tool | Verdict | Mechanism | Enterprise enforcement |
|---|---|---|---|
| **Cursor** | ✅ full | `beforeSubmitPrompt` hook (Cursor 1.7+), `hooks.json`; stdin JSON incl. `prompt`, `conversation_id`, `generation_id`, `workspace_roots[]`, **`user_email`**, `transcript_path`, `attachments[]`, `model` | OS managed dirs + Team dashboard sync (precedence Enterprise→Team→Project→User) |
| **Codex** | ✅ CLI | `UserPromptSubmit` hook (experimental), `~/.codex/config.toml [hooks]` or `hooks.json`; stdin `prompt`, `session_id`, `cwd`, `model`, `transcript_path` | `requirements.toml` + `allow_managed_hooks_only=true` + MDM; **IDE extension has no hooks** (session JSONL only — out of scope) |
| **GitHub Copilot** | ⚠️ partial | `UserPromptSubmit` hook **only on CLI / cloud agent / VS Code agent-mode (Preview)**; stdin `prompt`, `sessionId`, `cwd`, `timestamp`; **policy hooks cannot be disabled** | policy hooks (machine-wide, admin) — **but classic chat / inline completions / JetBrains have NO hook and cannot be captured client-side; the GitHub audit log has metadata only, no prompt text** |

**The Copilot gap is a first-class finding, not an omission.** We build the adapter for the surfaces
that support the hook (CLI / cloud agent / VS Code agent-mode) and **document explicitly** that classic
Copilot Chat, inline completions, and JetBrains Copilot are **not capturable client-side** by any
supported mechanism (third-party VS Code extensions are blocked by API isolation; the admin audit log
carries no prompt text). We do not fake coverage we don't have.

## Goals / Non-goals

**Goals**
- `clients/cursor/`, `clients/codex/`, `clients/copilot/` — each a self-contained shell adapter mirroring
  `clients/claude-code/` (non-blocking, fail-open, spool-and-drain, identity enrichment, `event_id`,
  generated timestamp), reusing `PROMPT_AUDIT_*` config and the per-org ingest token.
- Per-tool hook-config + enterprise-enforcement templates and a deploy README.
- Honest coverage docs: the Codex-IDE and Copilot-classic-chat gaps stated plainly.

**Non-goals**
- Codex IDE / Copilot classic-chat capture (no supported client hook — documented as a gap).
- A network proxy / MITM capture path (out of scope; brittle, unsupported).
- Live verification — like Claude Code, these are **built to the documented API, not yet run on a real
  install** (see `clients/README.md`); each gets a tracking issue.
- Blocking/prevent mode — pure audit, always exit 0 (a future feature).

## Design

All three reuse the Claude Code adapter's shape; only three things differ per tool: **field extraction**,
**`event_id` source**, and the **hook-config + managed-enforcement templates**.

**Identity enrichment** (all): payload identity if the tool provides it (Cursor gives `user_email`),
else env `PROMPT_AUDIT_*` → `git config user.email/name` → OS user; all fail-open to null. Claude Code /
Codex / Copilot hook JSON carry no identity, so enrichment is essential there.

**`event_id`** (idempotency; server dedups):
- Cursor → `generation_id` (stable per submission) else `sha256(conversation_id|minute|prompt)`.
- Codex → `sha256(session_id|minute|prompt)` (no per-submit id documented).
- Copilot → `sha256(sessionId|timestamp|prompt)`.

**Field mapping → ingest contract:**

| ingest | Cursor | Codex | Copilot |
|---|---|---|---|
| `prompt` | `.prompt` | `.prompt` | `.prompt` |
| `session_id` | `.conversation_id` | `.session_id` | `.sessionId` |
| `cwd` | `.workspace_roots[0]` | `.cwd` | `.cwd` |
| `transcript_path` | `.transcript_path` | `.transcript_path` | — |
| `user_email` | `.user_email` → enrich | enrich | enrich |
| `hostname` | `hostname` | `hostname` | `hostname` |
| `timestamp` | generated | generated | `.timestamp` else generated |

**Enterprise enforcement templates:**
- Cursor: `hooks.json` for the OS managed dirs (macOS `/Library/Application Support/Cursor/`, Linux
  `/etc/cursor/`, Windows `C:\ProgramData\Cursor\`).
- Codex: `requirements.toml` with `allow_managed_hooks_only = true` + a `managed_dir` script.
- Copilot: a policy-hook config (machine-wide, admin, non-disableable), scoped to CLI/agent.

**Non-blocking:** every adapter returns "continue/allow" and exits 0 — a slow/failed audit never delays
or blocks a prompt.

## Security & privacy

Same as the other adapters: the only on-machine credential is the write-only per-org ingest token;
hooks are visible (not covert), consistent with the transparency stance (spec 0003); never commit a
real token to the bundled `config.conf`.

## Edge cases & failure modes

- Tool-version drift in field names (Cursor/Codex hooks are new/experimental) — fail-open on missing
  fields; re-verify at live-verification time.
- Cursor block-side-effect bugs — irrelevant to observe-only auditing (we always continue).
- Codex/Copilot on unsupported surfaces (IDE / classic chat) — no capture; documented, and #4 coverage
  will show those hosts as never-reporting if a roster expects them.
- Offline → spool-and-drain; missing `jq`/`curl` → pass through.

## Acceptance criteria / test plan

1. For each tool, a sample hook stdin JSON → `report-prompt.sh` produces a correct ingest payload
   (right field mapping + stable `event_id`) and POSTs it (validated against a mock server).
2. Spool-on-failure + drain round-trip works.
3. Non-blocking: always exit 0 (and the tool's "continue/allow" output where required, e.g. Cursor
   `{"continue": true}`).
4. Enterprise templates are valid; READMEs state the managed paths and the coverage gaps honestly.

## Alternatives considered

- **Native HTTP hook** (as documented for Claude Code) — Cursor/Codex/Copilot hooks are command-type;
  the shell adapter also enriches identity/dedup, which a raw HTTP post can't.
- **Codex session-JSONL tailing** / **Copilot OTel content capture** — real but async/surface-specific;
  noted as alternatives in the READMEs, not the v1 mechanism.
- **Network proxy** for Copilot classic chat — unsupported/brittle; out of scope.

## Migration / rollout

Additive: three new `clients/*` directories + docs. No server or schema change. Coverage (#4)
automatically starts seeing the new hosts once they report.

## Decisions (resolved 2026-07-17)

1. **Copilot: build for the supported hook surfaces** (CLI / cloud agent / VS Code agent-mode) and
   **document the classic-chat / inline-completions / JetBrains gap explicitly** — partial-but-honest
   beats nothing, and matches the project's coverage-honesty stance.
2. **One combined PR** for all three adapters (they share the pattern).
3. **One live-verification tracking issue per adapter** (like #15 for Claude Code), since each needs
   real-install confirmation on a different tool.
4. All three carry the ⚠️ "built to the documented API — not yet verified on a live install" status in
   `clients/README.md` and their own READMEs.
