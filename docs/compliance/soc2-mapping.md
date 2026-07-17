# SOC 2 — control mapping (template)

How prompt-audit's capabilities map to the SOC 2 **Trust Services Criteria**. A **template** to accelerate
your audit — confirm scope/sufficiency with your auditor. "Evidence" points at the feature/endpoint that
demonstrates the control operating; the [evidence pack](../specs/0007-audit-ready-kit.md)
(`GET /api/v1/evidence`) bundles most of it.

| TSC | Criterion (intent) | How prompt-audit addresses it | Evidence |
|---|---|---|---|
| **CC6.1** | Logical access — restrict to authorized users | Two separate auth schemes (write-only ingest token vs read-only admin JWT); tenant isolation; `viewer`/`auditor`/platform roles ([#3](../specs/0003-anti-surveillance-guardrails.md)) | `evidence.config.auth_model` + `admins`; login/RBAC |
| **CC6.7** | Restrict transmission/movement of information | Secret redaction at capture masks keys/tokens before storage ([#2](../specs/0002-secret-pii-redaction.md)); TLS at your proxy | `evidence.redaction`; `/transparency` |
| **CC7.1** | Detect configuration/vulnerability changes | Tamper-evident hash chain detects any post-hoc edit/delete of a record ([#1](../specs/0001-tamper-evident-storage.md)) | `evidence.integrity` (`ok` + head hashes); `/api/v1/integrity` |
| **CC7.2** | Monitor for anomalies | Reporting-coverage / gap detection surfaces hosts that went dark or never reported ([#4](../specs/0004-reporting-coverage-gap-detection.md)) | `evidence.coverage`; `/api/v1/coverage` |
| **CC7.3 / CC7.4** | Evaluate & respond to security events | Coverage gaps + integrity failures are surfaced explicitly for response | `evidence.coverage`, `evidence.integrity` |
| **CC4.1 / CC2.1** | Monitor controls; relevant information | The admin access log records every full-text view/export, with reason, hash-chained ([#3](../specs/0003-anti-surveillance-guardrails.md)) | `evidence.access_log`; `/api/v1/access-log` |
| **CC6.2 / CC6.3** | Provision/modify access | Platform-managed roles; role changes via API | `admins` list; tenant admin management |
| **C1.1 / C1.2 (Confidentiality)** | Protect confidential information | Redaction (#2) + access logging (#3) + the evidence pack carries no raw prompt text | `evidence.redaction`, `evidence.access_log` |
| **P-series (Privacy)** | Notice & choice; use limitation | Public `/api/v1/transparency` discloses what's captured; **no productivity scoring**; access is reason-gated | `/api/v1/transparency` |

**What you still own:** organizational policies, personnel/onboarding controls, the deployment
environment (encryption at rest, network controls, backups), retention policy, and vendor management.
