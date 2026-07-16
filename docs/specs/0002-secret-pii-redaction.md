# 0002 — Secret / PII redaction at capture

- **Status:** Accepted
- **Issue:** [#2](https://github.com/leofang2007-maker/prompt-audit/issues/2)
- **Author:** —
- **Created:** 2026-07-16

## Problem & motivation

Developers paste `.env` files, keys, tokens, and PII into AI coding assistants — the canonical
trigger for this whole category ("network controls saw nothing… zero controls at the interaction
level," [research #12](../research/reddit-2026-07.md)). But **we store every prompt verbatim**, which
creates a paradox: capturing all prompts **concentrates every leaked secret into one database**,
making the audit store itself the juiciest target and a compliance liability.

The community splits into a "log it" camp (record everything) and a "prevent it" camp ("just don't
have any copyable secrets"; [research #26](../research/reddit-2026-07.md)). Redaction-at-capture
serves **both**: keep the *evidence* that a secret was sent (who, when, which repo, what type)
without hoarding the *secret itself*. It's also the exact "prompt-level, semantic DLP" people ask
for ([research #20](../research/reddit-2026-07.md)).

## Goals / Non-goals

**Goals**
- Detect secrets (and optionally PII) in a prompt and **mask** them before the prompt is stored.
- Keep audit evidence: record that redaction happened + how many + which types.
- Configurable (off / mask / …); deterministic (so the tamper-evident chain still verifies).
- Good recall on common secret formats; tolerable false-positive rate (mask is non-destructive to audit).

**Non-goals**
- Perfect/semantic DLP or ML-based PII detection (v1 is patterns + entropy).
- **Stopping the leak to the AI vendor.** Our hook reports *alongside* the tool; the raw prompt still
  goes to the vendor. Preventing that needs a *blocking/rewriting* client hook — a separate feature
  (relates to #3 and a future "prevent" mode). v1 protects the **audit store** from hoarding.

## Design

**Detection (v1): patterns + entropy.** A `Redactor` scans the prompt for a curated ruleset, each
match replaced by a typed token, e.g. `[REDACTED:aws_key]`. Starter ruleset:
- AWS access key `AKIA[0-9A-Z]{16}`; GitHub tokens `ghp_…`/`github_pat_…`/`gho_…`/`ghs_…`;
  private-key blocks `-----BEGIN … PRIVATE KEY-----…-----END…`; JWTs `eyJ…\.eyJ…\.…`;
  Stripe `sk_live_…`, Google `AIza…`, Slack `xox[baprs]-…`;
  `KEY=value` / `password=…` / `secret=…` / `token=…` assignments;
  generic high-entropy base64/hex tokens ≥ N chars.
- PII (emails, phone numbers) — **optional/off by default** (high false-positive; `user_email` is
  legitimately captured elsewhere).

**Where (the fork).** Two layers, and they compose:
- **Server-side at ingest (v1):** redact on the way into storage. Centralized, simple, immediately
  stops hoarding; the raw prompt reaches the server transiently over TLS (never logged — we log
  length only) but is masked before it's persisted.
- **Client-side in the hook (follow-up):** mask before the POST, so the raw secret never leaves the
  developer's machine *to us*. Strongest, but detection ships/maintained per client (shell) — later.

**Modes** (`app.redaction.mode`): `off` | `mask` | `hash` (replace with a short salted hash so
repeats are detectable without the value). `reject` is intentionally not a default (it would drop the
audit record entirely — the opposite of the goal).

**Record metadata** (new columns): `redaction_count INT`, `redacted_types VARCHAR` (e.g.
`aws_key,jwt`). Surfaced in the detail (and optionally list) so an admin sees "3 secrets redacted".

**Ordering with the chain (spec 0001):** on ingest — dedup check → **redact** → set prompt_length →
hash → chain. So the *redacted* prompt is what's stored, hashed, and chained. Redaction must be
deterministic for the chain to verify.

## Security & privacy

- Cuts the **at-rest** hoarding risk (the main one): a compromised DB / over-broad admin no longer
  yields a pile of live secrets.
- Server-side mode: the raw prompt is in the request body transiently; we never log it (length only).
  Client-side mode (follow-up) removes even that.
- Honest boundary: does **not** stop the secret reaching the AI vendor (that's a blocking hook,
  out of scope here).
- `hash` mode uses a server-side salt so masked values can't be brute-forced from the hash.

## Edge cases & failure modes

- Overlapping matches / greedy regex / very long prompts (perf) — bound work; apply longest-match.
- False positives on normal high-entropy text → per-type toggles, `mask` (non-destructive) default,
  conservative entropy threshold.
- Determinism: same input → same output (no timestamps/randomness in `mask`), else the chain breaks.
- Deduped repeats: dedup runs first (returns the existing, already-redacted record); redaction only
  on new inserts.

## Acceptance criteria / test plan

1. A prompt containing an AWS key / GitHub token / private key is stored with each secret replaced by
   its typed token; the raw secret is **not** present in the stored prompt; `redaction_count` ≥ 1.
2. `mode=off` → stored verbatim, `redaction_count=0`.
3. A clean prompt (normal prose/code, no secrets) is stored unchanged (no false redaction).
4. Redaction is deterministic — the tamper-evident chain still verifies after redaction.
5. `redacted_types` / `redaction_count` surface in the detail API.

## Alternatives considered

- **Reject on secret** — loses the audit record; worse than masking.
- **DB encryption at rest only** — doesn't help against a compromised app/DB or an over-broad admin;
  still hoarding plaintext logically.
- **ML/NER PII detection** — heavier, higher infra; revisit for a PII-focused follow-up.
- **Client-only redaction** — strongest for in-transit, but loses centralized control; ship as the
  composing follow-up, not the v1 base.

## Migration / rollout

Hibernate adds the two columns. Redaction applies to **new** ingests only (existing rows are
unchanged — they were captured before the feature). Default mode is an open question (below).

## Decisions (resolved 2026-07-16)

1. **Layer: server-side at ingest** for v1; client-side (before POST) redaction is a follow-up.
2. **Default mode: `mask` (secure-by-default)** — but with a **high-confidence, low-false-positive
   ruleset only.** No generic high-entropy heuristic in v1 (that's the false-positive risk); the
   built-in rules are well-formed, prefixed secret formats + explicit credential assignments. So a
   normal prompt is never mangled by default.
3. **Ship `mask` first** (`[REDACTED:type]`); `hash` mode is a later follow-up.
4. **`prompt_length` = the redacted (stored) length**; `redaction_count` signals something was removed.
5. **Built-in ruleset + operator-extensible** via config (`app.redaction.extra`, `type=regex` entries).
6. **Secrets-only in v1.** PII (emails/phones) is opt-in / later — high FP and `user_email` is
   legitimately captured elsewhere.
7. **Warn-the-developer is out of scope** (that's prevention/blocking; relates to #3 + a future
   "prevent" mode).

**Follow-ups (not in this spec):** client-side redaction; `hash` mode; PII detectors; a blocking
"prevent" mode that stops the secret reaching the vendor.
