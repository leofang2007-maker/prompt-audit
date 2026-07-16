# 0001 — Tamper-evident audit storage

- **Status:** Draft
- **Issue:** [#1](https://github.com/leofang2007-maker/prompt-audit/issues/1)
- **Author:** —
- **Created:** 2026-07-16

## Problem & motivation

An audit trail is only worth anything if it can't be silently altered or deleted after the fact.
Vendor logs have failed exactly this test — [Copilot silently broke its audit log](https://www.reddit.com/r/programming/comments/1mv7cxz/copilot_broke_your_audit_log_but_microsoft_wont/)
(890▲), *"access a file without leaving a trace."* The [research](../research/reddit-2026-07.md)
ranks a **trustworthy, tamper-evident** trail as the #2 need. Today, anyone with write access to the
`prompt_record` table (a DBA, an attacker, or a nervous admin) can delete or edit rows and leave no
sign. This spec makes such tampering **detectable**.

## Goals / Non-goals

**Goals**
- Detect, after the fact, any **deletion**, **modification**, or **reordering** of stored records.
- Zero external dependencies; works with our existing MySQL + Spring Boot/JPA.
- An admin can **verify** the log and get pointed at the first break.
- Honest, documented guarantee and its limits.

**Non-goals**
- Tamper-**proof** / prevention. We make tampering *evident*, not impossible (see Security).
- Confidentiality / redaction of prompt content — that's [#2](https://github.com/leofang2007-maker/prompt-audit/issues/2).
- External notarization / WORM media / blockchain (possible later; see Alternatives).
- Chaining the assistant's *response* — we only store prompts today.

## Design

**Hash chain.** Each record gets a `record_hash` computed over its own content **plus the previous
record's hash**, so the records form a chain:

```
record_hash = sha256( canonical(record) || prev_hash )
```

- `prev_hash` = the `record_hash` of the immediately preceding record in the same chain; the first
  record uses a fixed genesis constant (e.g. 64 zeros).
- Deleting a middle record breaks the next record's `prev_hash` linkage. Editing a record changes
  its `record_hash`, so the *next* record's `prev_hash` no longer matches. Reordering breaks linkage.
  All three become detectable by re-walking the chain.

**Schema** (added to `prompt_record`, all Hibernate-managed):
- `record_hash CHAR(64)` — hex sha256 of this record.
- `prev_hash CHAR(64)` — the previous record's hash in the chain.
- `chain_seq BIGINT` — monotonic position within the chain (for ordering + gap detection).
- index on `(<chain-key>, chain_seq)`.

**`canonical(record)`** — a deterministic serialization of the fields we want protected. Must be
stable forever (it's a compatibility surface). Strawman: the persisted fields in a fixed order,
UTF-8, joined by a delimiter that can't appear unescaped — `id, event_id, tenant_org_id, timestamp,
received_at, session_id, user_email, user_name, user_uid, org_id, org_name, repo, branch, cwd,
transcript_path, hostname, prompt_length, prompt`. (Exact format is an open question.)

**Ingest change.** On insert, within the ingest transaction: read the current chain head, compute
`record_hash`, set `prev_hash`/`chain_seq`, persist, and advance the head. This must be atomic per
chain (see Edge cases).

**Verification.** `GET /api/v1/integrity` (admin): walk the chain in `chain_seq` order, recompute
each `record_hash`, check each `prev_hash` matches the predecessor and `chain_seq` has no gaps.
Return `{ ok, chain, checked, first_broken_id? , head_hash }`. Org admins verify their own tenant's
chain; the platform admin can verify any/all.

## Security & privacy

- **This is tamper-*evident*, not tamper-*proof*.** An attacker with full DB write can edit a
  record and **re-chain everything after it**, producing a consistent (but forged) chain. The chain
  alone only catches lazy tampering. To bound this, we **anchor** the head: periodically emit the
  current `head_hash` to somewhere the DB attacker can't also silently rewrite (append-only log
  line, email/webhook to the security team, an external store). Then any re-chaining that predates
  an anchor is caught, because the recomputed head won't match the anchored value. Whether v1 ships
  anchoring or just the chain is an **open question**.
- The hash covers `prompt` too, so **edits to prompt text are detected** — but a hash doesn't expose
  content, so `record_hash`/`prev_hash` are safe to expose to admins/logs.
- Interaction with [#2 redaction](https://github.com/leofang2007-maker/prompt-audit/issues/2):
  redaction happens **before** hashing — the redacted prompt is what's stored *and* hashed. Ordering
  dependency to note when both land.

## Edge cases & failure modes

- **Concurrency (the hard one).** Two concurrent ingests on the same chain must not read the same
  `prev_hash` and fork it. Options: (a) a `chain_head` row per chain with `SELECT … FOR UPDATE` in
  the ingest txn (serializes ingest *per chain*); (b) a unique constraint on `(chain_key, chain_seq)`
  + optimistic retry on conflict. Prompts are human-paced, so per-chain serialization is likely
  fine — but it's a throughput trade-off worth stating.
- **Idempotent/deduped ingests** ([event_id](../../README.md#data-model)): a deduped POST returns an
  existing record and must **not** append a new chain link.
- **Existing rows.** The live DB already has un-chained rows — needs a one-time backfill (see Migration).
- **Verification cost** on a large table: full walk is O(n). Provide incremental/anchored verification
  (verify only since the last good anchor) as a follow-up.

## Acceptance criteria / test plan

1. Ingesting N records produces a chain: each `record_hash` recomputes correctly and each
   `prev_hash` links to its predecessor; `GET /api/v1/integrity` → `ok: true`, `checked: N`.
2. Editing a middle row's `prompt` directly in the DB → integrity reports `ok: false` with the
   broken record's id.
3. Deleting a middle row → integrity reports a break (linkage/`chain_seq` gap).
4. Concurrent ingests on one chain yield a single valid linear chain (no fork / duplicate seq).
5. A deduplicated repeat POST adds **no** new chain link.
6. `GET /api/v1/integrity` requires admin auth and is tenant-scoped for org admins.

## Alternatives considered

- **Per-record HMAC signature** (server key) instead of a chain: detects edits, but not deletion or
  reordering on its own, and if the key lives on the same server the attacker gets it. A chain is
  stronger for deletion/reorder; we could *combine* (sign the head) later.
- **Merkle tree / periodic Merkle roots:** efficient inclusion proofs at scale, but more complex than
  a chain; revisit if verification cost or third-party proofs matter.
- **External transparency log / blockchain notarization:** strong anchoring, heavy for v1; the
  lightweight version is our "anchor the head" idea.

## Migration / rollout

- Hibernate `ddl-auto=update` adds the columns. Ship a **one-time backfill** that walks existing
  rows in `received_at` order (per chain) and computes the chain. Until backfilled, integrity checks
  treat pre-chain rows as an explicit "unchained (pre-feature)" segment rather than a break.
- Additive and safe: no data is dropped; deploying the new image doesn't reset anything.

## Open questions

1. **Chain scope:** one **per-tenant** chain (matches multi-tenant isolation, better concurrency,
   loses global ordering) vs one **global** chain (simplest, single sequence, serializes all ingest)?
   *Leaning per-tenant, with the global/bootstrap-token data as its own chain.*
2. **Concurrency:** `SELECT … FOR UPDATE` on a `chain_head` row, or `chain_seq` unique + retry?
3. **Anchoring in v1?** Ship head-anchoring (log line / webhook to security) now, or chain-only first
   and anchoring as a fast follow? If yes, what's the default anchor sink?
4. **Backfill** the existing rows, or start the chain fresh from deploy (mark old rows pre-chain)?
5. **Canonical form:** lock the exact serialization + field set now (changing it later invalidates
   verification of old rows). JSON-canonical vs a delimited string?
6. Scope creep check: do we also want a **separate append-only `access_log`** (who viewed which
   prompt) chained the same way? That overlaps [#3](https://github.com/leofang2007-maker/prompt-audit/issues/3) — keep here or there?
