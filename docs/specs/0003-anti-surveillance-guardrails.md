# 0003 — Anti-surveillance guardrails

- **Status:** Draft
- **Issue:** [#3](https://github.com/leofang2007-maker/prompt-audit/issues/3)
- **Author:** —
- **Created:** 2026-07-16

## Problem & motivation

This product is **bought by security/compliance but can be killed by developer revolt.** The strongest,
highest-engagement emotion in the whole [Reddit research](../research/reddit-2026-07.md) is developer
*resistance* to prompt capture — a 1,270▲ warning that employers read every message incl. "incognito"
ones, met with resignation not reassurance ([#1](https://www.reddit.com/r/ClaudeAI/comments/1spsugm/ysk_if_you_use_claude_on_your_companys_enterprise/)),
and a 207▲ "dystopic hellscape" thread about LLM-scoring developers ([#3](https://www.reddit.com/r/ExperiencedDevs/comments/1oiaevf/is_your_company_using_llms_to_track_monitor_and/)).
The people who *buy* prompt-auditing are a different population from those who must *live under it*, and
the latter route around controls (personal hotspots/laptops) the moment it feels like spyware — at which
point capture goes to zero ([research §13](../research/reddit-2026-07.md)).

The research's single most important caveat: trust, transparency, redaction and an "enable, don't
surveil" stance are **not nice-to-haves, they're the wedge** ([§96](../research/reddit-2026-07.md)).
[#2](0002-secret-pii-redaction.md) removed the *secret-hoarding* liability; **#3 removes the
*misuse-into-surveillance* liability** — it builds guardrails so the system *cannot* easily be turned
into keystroke monitoring or productivity scoring, and so an admin's use of it is itself accountable.

## Goals / Non-goals

**Goals**
- Make every admin view of prompt content **accountable**: who looked, at what, when, and *why* — itself
  an audit record (the "who watches the watchers" property).
- **Least privilege by default:** seeing a prompt's full text is a deliberate, gated action, not the
  default browsing experience.
- Make the "no productivity/performance scoring" stance a **structural** property, not just a promise.
- Give the deploying org the primitives to be **transparent to developers** about what's captured.

**Non-goals**
- Per-developer productivity metrics, leaderboards, or scoring surfaces — explicitly never built.
- Full enterprise IAM (SSO/SAML/SCIM) — that's [#7](https://github.com/leofang2007-maker/prompt-audit/issues/7).
- Preventing a determined malicious DBA at the SQL layer — that's the tamper-evident chain's job
  ([#1](0001-tamper-evident-storage.md)); here we govern the *application's* admins.

## Design

**1. Access audit log (the centerpiece) — `admin_access_log`.** Every privileged read of prompt content
— opening a detail, running an export — writes an append-only row: `{id, actor_admin_id, actor_email,
actor_role, tenant_org_id, action (view_detail|export), target_record_id | query_json, reason, ip,
created_at}`. Surfaced to the tenant owner (and platform admin) as its own view: *"who looked at what,
and why."* Listing redacted previews does **not** log (that's ordinary triage); revealing full prompt
text or exporting **does**.

**2. Reason-required access (break-glass).** `GET /api/v1/prompts/{id}` and `/export` require a
non-empty `reason`; the server rejects without it and stores it in the access log. Turns "idly browse
everyone's prompts" into a deliberate, recorded act. Configurable strictness
(`app.access.require-reason=true` default).

**3. Least-privilege roles (within a tenant).** Today a tenant's admins are uniform. Introduce a role on
`admin_user`: **`viewer`** (list + redacted previews + metadata, but full prompt text is masked) vs
**`auditor`** (may reveal full text / export, always reason-logged). Tenant owner assigns roles. Default
new admins to the lower privilege.

**4. No-scoring, structurally.** No endpoint aggregates prompts *per developer* into a score/ranking; the
API exposes compliance search (by time/user/repo/keyword) but not "rank users by volume." Documented as a
first-class product stance in the README + a `SECURITY`/`PRINCIPLES` note.

**5. Developer transparency.** A read-only disclosure surface (e.g. `GET /api/v1/transparency`) stating
what fields are captured, that secrets are redacted ([#2](0002-secret-pii-redaction.md)), retention, and
that admin access is itself logged — so an org can honestly tell developers "here's exactly what this
sees." (Scope of v1 is an open question.)

**Interaction with #1 (tamper-evident chain).** The access log should itself be **hash-chained** (same
mechanism as spec 0001) so an admin can't scrub the record that they looked — "you can't un-see without
a trace." Retention/auto-purge, by contrast, *conflicts* with append-only storage — see open questions.

## Security & privacy

- The access log must not be editable by the actor it records; ideally chained so tampering is detectable.
- Platform-admin reads are logged too (no silent super-user bypass) — the log records `actor_role`.
- The `reason` is free text and may itself be sensitive (mentions an incident/person) — treated as
  audit data, same access rules as prompt content.
- Least-privilege must fail closed: an admin with no role, or an unknown role, gets the *viewer* (masked)
  experience, never full text.

## Edge cases & failure modes

- Legit incident response needs fast full-text access — break-glass allows it *with* a reason, doesn't
  block it.
- Export of many rows = one access-log entry describing the query (not one per row), or it floods.
- A `viewer` must not be able to reach full text via export/keyword-highlight side channels.
- Backfill: existing admins get a default role on migration (owner = auditor, others = viewer? — open q).

## Acceptance criteria / test plan

1. Opening a detail / exporting **without** a reason → rejected (400); **with** a reason → succeeds and
   writes exactly one `admin_access_log` row capturing actor, target, reason, action.
2. A `viewer`-role admin sees list + redacted previews but **cannot** retrieve full prompt text; an
   `auditor` can (reason-logged).
3. Platform-admin full-text reads are logged too (no bypass).
4. The access log is visible to the tenant owner and is append-only (and, if chained, verifies).
5. No API endpoint returns a per-developer score/ranking.

## Alternatives considered

- **Policy/docs only** (promise "we won't misuse it") — no structural guarantee; doesn't neutralize the
  backlash. Rejected as the whole point is *structural* trust.
- **Full RBAC/ABAC engine** — heavier than v1 needs; defer granularity, ship viewer/auditor + access log.
- **Encrypt prompts, decrypt-on-view with escrow** — strong but complex; the access log + roles get most
  of the trust benefit far cheaper. Revisit later.

## Migration / rollout

Adds `admin_access_log` (+ optional chain columns) and a `role` column on `admin_user` (Hibernate
ddl-auto). `require-reason` defaults on. Existing admins get a default role on first boot.

## Open questions

1. **RBAC depth:** ship just `viewer`/`auditor` + access log, or a finer role model now? *(leaning
   viewer/auditor — minimal, demoable, covers the trust story.)*
2. **Reason required where:** every full-text detail view + every export, or only exports / bulk? *(leaning
   both detail + export, configurable.)*
3. **Developer transparency in v1**, or admin-side guardrails first and the dev-facing surface later?
   *(leaning: ship the `/transparency` disclosure endpoint + README stance in v1; richer dev self-view later.)*
4. **Chain the access log?** Reuse spec 0001's hash chain for `admin_access_log` so admin views are
   themselves tamper-evident. *(leaning yes — it's the same primitive and strongly on-theme.)*
5. **Retention / purpose-limitation:** in scope? It **conflicts** with append-only tamper-evident storage
   (deleting old rows breaks the chain). Options: out of scope for #3; or a sanctioned, logged,
   chain-re-anchoring purge as a separate spec. *(leaning: out of scope here, note as a follow-up.)*
6. **Default role on migration** for existing admins: owner→auditor, others→viewer? Or all→auditor to
   avoid surprising current deployments, and let owners downgrade? *(leaning owner=auditor, others=viewer,
   but call it out since it changes current behavior.)*
