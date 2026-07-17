# ISO/IEC 27001:2022 — Annex A control mapping (template)

How prompt-audit's capabilities map to **ISO 27001:2022 Annex A** controls. A **template** to accelerate
your audit — confirm scope/sufficiency with your auditor. The [evidence pack](../specs/0007-audit-ready-kit.md)
(`GET /api/v1/evidence`) bundles most of the evidence.

| Annex A | Control (intent) | How prompt-audit addresses it | Evidence |
|---|---|---|---|
| **A.5.15** | Access control | Two auth schemes + tenant isolation + `viewer`/`auditor`/platform roles ([#3](../specs/0003-anti-surveillance-guardrails.md)) | `evidence.config.auth_model`, `admins` |
| **A.5.16 / A.5.18** | Identity & access rights | Per-org admins; platform-managed role assignment/changes | `admins` list |
| **A.8.15** | Logging | Every full-text view/export is logged with actor + reason, hash-chained ([#3](../specs/0003-anti-surveillance-guardrails.md)) | `evidence.access_log`; `/api/v1/access-log` |
| **A.8.16** | Monitoring activities | Coverage / gap detection: hosts that went dark or never reported ([#4](../specs/0004-reporting-coverage-gap-detection.md)) | `evidence.coverage`; `/api/v1/coverage` |
| **A.8.10 / A.8.12** | Information deletion / DLP | Secret redaction masks keys/tokens before storage ([#2](../specs/0002-secret-pii-redaction.md)) — the store never holds them | `evidence.redaction`; `/transparency` |
| **A.5.28 / A.8.15** | Collection of evidence; log integrity | Append-only, tamper-evident hash chain ([#1](../specs/0001-tamper-evident-storage.md)); the evidence pack is self-hashed + anchored | `evidence.integrity`, `evidence.bundle_hash` |
| **A.5.33** | Protection of records | Records are immutable + verifiable; tampering is detectable | `evidence.integrity` (`ok`, `first_broken_id`) |
| **A.5.34** | Privacy & PII protection | Redaction (#2); public transparency disclosure; no productivity scoring; reason-gated access | `/api/v1/transparency`, `evidence.redaction` |
| **A.5.7 / A.5.25** | Threat intel; assess security events | Integrity failures + coverage gaps surfaced for response | `evidence.integrity`, `evidence.coverage` |

**What you still own:** ISMS scope, risk assessment/treatment, policies, personnel controls, the
deployment environment (crypto at rest, backups, network), retention, and supplier management.
