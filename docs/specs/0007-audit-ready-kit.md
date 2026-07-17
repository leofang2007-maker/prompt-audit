# 0007 — Audit-ready kit (control mappings + evidence export)

- **Status:** Accepted
- **Issue:** [#6](https://github.com/leofang2007-maker/prompt-audit/issues/6)
- **Author:** —
- **Created:** 2026-07-17

## Problem & motivation

The buyers are security/compliance teams operating under **SOC 2** and **ISO 27001** (and adjacent
regulatory) audits. Engineering managers fear being "**one audit away from an uncomfortable
conversation**" ([research #4](../research/reddit-2026-07.md)); regulated orgs (e.g. fintech) explicitly
shop for compliance-grade AI usage visibility ([#10](../research/reddit-2026-07.md)); and "audit-ready"
+ "exportable" are recurring asks.

We've *built* strong controls — tamper-evident storage ([#1](0001-tamper-evident-storage.md)),
secret redaction ([#2](0002-secret-pii-redaction.md)), a hash-chained admin access log
([#3](0003-anti-surveillance-guardrails.md)), coverage/gap detection
([#4](0004-reporting-coverage-gap-detection.md)). But an auditor needs two more things we don't yet
package: **(a)** which of *their* control requirements these satisfy, and **(b)** evidence the controls
are *operating* over a period. #6 is that packaging: a **control-framework mapping** + a one-click,
tamper-anchored **evidence pack**.

## Goals / Non-goals

**Goals**
- **Control mappings** (in-repo docs): SOC 2 Trust Services Criteria + ISO 27001 Annex A → the product
  capability that addresses each, and where the evidence lives.
- **Evidence export**: a tenant-scoped, point-in-time bundle proving the controls operate — integrity
  verification, access-log summary, coverage snapshot, redaction stats, record counts, config
  attestation — as machine-readable JSON, self-anchored by hashes, downloadable + human-readable.
- Generating evidence is **auditor-gated and itself access-logged** (#3), and the act is part of the
  evidence.

**Non-goals**
- Being a GRC platform or issuing certifications — we provide mappings + evidence, not the audit.
- Legal/compliance advice — the mapping is a **starting template**; "confirm with your auditor."
- A cryptographic (PKI) signature in v1 — self-hashing + embedding the chain head hashes is the v1
  tamper anchor (real signing is a follow-up).

## Design

**1. Control mappings** — `docs/compliance/`:
- `soc2-mapping.md`, `iso27001-mapping.md`, plus a short `README.md`. Each is a table:
  `control id → control intent → how prompt-audit addresses it → evidence (endpoint/feature)`.
- Examples: SOC 2 **CC7.2** (monitoring) ← prompt capture + coverage (#4); **CC6.1** (logical access) ←
  two-scheme auth + viewer/auditor RBAC (#3); audit-logging criteria ← the tamper-evident access log
  (#3) + storage chain (#1); **CC6.7 / confidentiality** ← redaction (#2). ISO 27001 **A.8.15
  (logging)**, **A.8.16 (monitoring)**, **A.5.15 (access control)**, etc. Clearly marked as a template.

**2. Evidence export API** — `GET /api/v1/evidence?from=&to=` (auditor/platform only, tenant-scoped,
access-logged). Returns a JSON bundle aggregated from existing data (no new tables):

```
{
  generated_at, period: {from, to}, tenant,
  integrity:      { ok, chains:[{chain, ok, checked, head_hash, first_broken_id}] },   // spec 0001 verify
  access_log:     { total, by_action:{view_detail, export}, chain_ok, head_hashes },    // spec 0003
  coverage:       { total_hosts, active_hosts, went_dark, never_reported },             // spec 0004
  redaction:      { records_with_redactions, secrets_masked, by_type:{...} },           // spec 0002
  records:        { total_in_period, total_all_time },
  config:         { redaction_mode, require_reason, retention, auth_model, roles },      // attestation
  bundle_hash:    sha256(canonical(everything above))                                    // self-anchor
}
```

The `bundle_hash` + the embedded chain head hashes make the pack tamper-evident: re-running it yields the
same anchors iff nothing was altered.

**3. Rendering + UI** — the server returns JSON; the UI renders a human-readable **evidence report**
(printable) and offers **download JSON**. An "Evidence" (Compliance) page: pick a period → Generate →
summary cards + control-mapping links + downloads. (Server stays JSON-only; HTML/PDF rendering is
client-side, keeping the server simple.)

**4. Minimal server additions** — mostly aggregation over existing repos; add a couple of count queries
(records in period; redaction stats: count where `redaction_count > 0`, sum, by type). Reuse
`PromptService.verify` (#1), `AccessLogService` (#3), `CoverageService` (#4).

## Security & privacy

- Evidence generation requires the **auditor** role (or platform) and is written to the access log —
  producing evidence is itself audited.
- The pack is a **summary/attestation**, not a dump of prompt text — it carries counts, hashes, and
  config, not raw prompts (so the evidence pack isn't itself a secret-hoard). Raw export stays the
  separate, reason-gated `/prompts/export`.
- Tenant-scoped exactly like every other read.

## Edge cases & failure modes

- Empty period / new tenant → zeroed sections, still a valid (verifiable) bundle.
- Broken chain → `integrity.ok=false` surfaced prominently (the evidence honestly shows a problem).
- Very large access log in period → summary + counts (not every row) to bound size; full detail remains
  in `/access-log`.
- Clock/timezone → periods in UTC, `received_at`-based.

## Acceptance criteria / test plan

1. `GET /api/v1/evidence` (auditor) returns a bundle with all sections populated from seeded data;
   counts/coverage/redaction/integrity match the underlying state.
2. A `viewer` is denied; the generation is recorded in the access log.
3. Tenant scoping: an org auditor's bundle covers only their tenant.
4. `bundle_hash` is deterministic for identical state and changes when underlying data changes.
5. The mapping docs exist and link each control to a real capability/endpoint.

## Alternatives considered

- **Full GRC integration (Vanta/Drata/etc.)** — valuable later (export *to* them), but v1 owns a
  self-contained, portable evidence pack first.
- **Server-rendered PDF** — heavier deps; client-side print-to-PDF from the HTML report suffices for v1.
- **Real cryptographic signing** — stronger, but self-hash + chain anchors give tamper-evidence now;
  signing is a follow-up.

## Migration / rollout

Additive: new read endpoint + `docs/compliance/` + a UI page. One or two aggregate queries; no schema
change. No ingest change.

## Decisions (resolved 2026-07-17)

1. **Bundle contents:** integrity (#1) + access-log summary (#3) + coverage snapshot (#4) + redaction
   stats (#2) + record counts + config attestation + **admins & roles list** (cheap, auditor-useful).
   **Summary/counts/hashes only — no raw prompt text** (the evidence pack must not itself be a
   secret-hoard); raw export stays the separate reason-gated `/prompts/export`.
2. **JSON only from the server**; the UI renders a printable HTML report + offers JSON download.
   PDF/CSV are follow-ups (browser print-to-PDF suffices).
3. **Tamper anchor:** a `bundle_hash` (self-hash of the content) + embedded chain head hashes (#1/#3).
   Real PKI signing is a follow-up.
4. **Frameworks in v1:** SOC 2 (Trust Services Criteria) **and** ISO 27001 (Annex A); NIST 800-53 / GDPR
   later. Marked as templates ("confirm with your auditor").
5. **Access:** auditor + platform only (viewers denied); generation is written to the access log.
6. **Period:** arbitrary `from`/`to`, default the last 90 days.
