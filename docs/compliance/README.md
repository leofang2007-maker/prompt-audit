# Compliance / audit-ready kit

Two things auditors need that this project packages (spec [0007](../specs/0007-audit-ready-kit.md)):

1. **Control-framework mappings** — which of *your* control requirements the product's capabilities
   address, and where the evidence lives:
   - [SOC 2 (Trust Services Criteria)](soc2-mapping.md)
   - [ISO 27001 (Annex A)](iso27001-mapping.md)
2. **An evidence pack** — a tenant-scoped, point-in-time bundle proving the controls are *operating*:
   `GET /api/v1/evidence?from=&to=` (auditor/platform; itself access-logged). It aggregates integrity
   verification (#1), access-log summary (#3), coverage (#4), redaction stats (#2), record counts, and a
   config attestation, anchored by a `bundle_hash` + the tamper-evident chain head hashes. It contains
   **summaries, counts, and hashes only — never raw prompt text** (so the evidence pack is not itself a
   sensitive data hoard; raw export stays the separate, reason-gated `/api/v1/prompts/export`).

> ⚠️ **These mappings are a starting template, not a certification or legal advice.** Confirm scope and
> sufficiency with your own auditor. Controls are addressed by the *product*; you still own process,
> deployment, retention policy, and personnel controls.

The UI's **Evidence** page lets an auditor pick a period, generate the pack, view a printable report,
and download the JSON.
