# 0004 — Reporting-coverage / gap detection

- **Status:** Draft
- **Issue:** [#4](https://github.com/leofang2007-maker/prompt-audit/issues/4)
- **Author:** —
- **Created:** 2026-07-16

## Problem & motivation

This system only sees **what is reported** — and the highest-risk behavior is precisely what *isn't*.
A developer who wants to route around the control uses a personal hotspot / unmanaged laptop, and
"network controls saw nothing because the data left through an encrypted browser session"
([research #12](../research/reddit-2026-07.md)); "shadow AI" is exactly what security is hunting for
([#19](../research/reddit-2026-07.md)); banning tools just produces people "secretly using it, making
the situation even worse" ([#5](../research/reddit-2026-07.md)).

So a compliance dashboard that shows "all green, N prompts captured" is **false assurance**. The
auditor's first question — *"how do you know you're seeing everything?"* — has no answer today. #4
turns **absence of evidence** into an explicit signal: not just "we captured 10k prompts" but "…and
here are the 6 machines/users that were reporting and went dark, and the ones that never reported at
all." It's what makes the audit trail defensible as *evidence* rather than just a pile of what happened
to arrive.

## Goals / Non-goals

**Goals**
- Detect **coverage gaps**: entities that *should* be reporting but aren't — went-dark, never-reported,
  stale client, and volume anomalies.
- Derive most of this from **existing data** (`prompt_record` already has hostname / user / session /
  tenant), so no new capture is required for v1.
- Surface it tenant-scoped via a coverage endpoint the audit UI can render.
- Frame and present it as **coverage/security posture**, NOT per-developer activity monitoring (see the
  #3 tension below — this is the load-bearing design constraint).

**Non-goals**
- Per-developer productivity/activity scoring or "who's quiet this week" (that's the exact surveillance
  #3 guards against — a hard non-goal).
- IdP/SCIM roster sync (that's [#7](https://github.com/leofang2007-maker/prompt-audit/issues/7)); v1
  rosters, if any, are a manual import.
- Client-side heartbeat (a follow-up — v1 infers coverage server-side from prompt cadence).
- Eliminating the blind spot entirely — impossible; see honest limits.

## Design

**Kinds of gap:**

| Kind | Meaning | Detection |
|------|---------|-----------|
| **went-dark** | An entity that reported regularly, then stopped (hook removed, machine reimaged, moved to a personal device) | per-entity `last_reported_at` + learned cadence; silent > threshold |
| **never-reported** | An entity on an *expected roster* that has never reported (shadow AI / unmanaged) | diff active-set vs an imported roster |
| **stale-client** | Reporting, but on an old plugin version / a tool with no adapter | client version in the payload (depends on [#5](https://github.com/leofang2007-maker/prompt-audit/issues/5)) |
| **volume-drop** | Sharp drop vs the entity's own baseline (partial silence) | rolling baseline comparison |

**Entity granularity.** The unit of coverage is the **machine (`hostname`)** by default — "is the
control working on this host" — not the person. Whether reports may drill down to a *user* is an open
question (the #3 tension).

**Derivation (v1, server-side).** A `CoverageService` computes, per tenant, from `prompt_record`:
- the **active set** (entities seen within the recent window),
- each entity's **last-seen** and **typical interval** (median gap between its reports),
- **went-dark** = active historically but silent for > `k ×` its typical interval (with a floor, e.g.
  ≥ 24h, to avoid noise),
- **volume-drop** = recent rate << baseline rate.

**Expected roster (optional).** An admin can upload a roster (CSV of expected hostnames/users). The
service then also reports **never-reported** (on roster, never seen) and **expected-but-silent**.
Without a roster, only cadence-based signals are available — stated plainly (no silent caps).

**API.** `GET /api/v1/coverage` (tenant-scoped) → `{ window, active_count, silent:[…], never_reported:[…],
stale_client:[…], generated_at }`, each entry `{ entity, kind, last_seen, expected_interval, ... }`.
Roster management endpoints if we ship the roster in v1.

**UI.** A "Coverage" view: a headline ("N hosts reporting; M went dark") + a table of gaps. Framed as
posture, not a leaderboard.

## Security & privacy

- **The #3 tension is the primary risk.** "Who went silent" trivially becomes "name-and-shame quiet
  developers." Mitigations: default aggregation by **host/coverage**, not by person; present as "the
  control is not working here," not "this person is inactive"; if user-level drill-down is allowed at
  all, gate it behind the **auditor** role and record it in the **access log** (spec 0003); document
  the coverage-not-surveillance stance in the transparency disclosure + README.
- Coverage reports are tenant-scoped exactly like the prompt list; org admins see only their tenant.
- An uploaded roster may contain names/emails — treat it as audit data (same access rules).

## Edge cases & failure modes

- **New/low-volume entities**: never build a stable baseline → don't false-flag; require a minimum
  history before "went-dark" can fire.
- **Legitimately quiet**: someone on vacation / not coding is *not* a gap — the point is the control's
  health on a host, not attendance. Tune thresholds conservatively; prefer host-level.
- **Bursty cadence**: median interval + a floor avoids flapping.
- **Roster drift**: a stale roster produces false "never-reported"; make roster clearly dated/optional.
- **Clock skew** between reporters: rely on server `received_at`, not client `timestamp`.

## Acceptance criteria / test plan

1. A host that reported repeatedly and then stopped for > threshold appears in `silent[]` with its
   `last_seen`; an actively-reporting host does not.
2. A brand-new host with little history is **not** false-flagged as went-dark.
3. With an uploaded roster, an entity on the roster that never reported appears in `never_reported[]`.
4. Coverage is tenant-scoped: an org admin sees only their tenant's gaps.
5. (If user drill-down ships) it requires the auditor role and writes an access-log entry.

## Alternatives considered

- **Client-side heartbeat** (ping even with no prompt) — most accurate at distinguishing "not coding"
  from "hook removed", but needs a client change + adapter work; defer as a follow-up.
- **Network/CASB correlation** — outside this tool's scope; we own the interaction layer, not the wire.
- **Do nothing / dashboard only** — leaves the false-assurance problem unsolved.

## Migration / rollout

Read-only derivation over existing `prompt_record` — no schema change for the core cadence signals. A
roster feature adds a small table. Nothing changes about ingest.

## Open questions

1. **Heartbeat vs cadence-only:** v1 = pure server-side cadence inference, client heartbeat as a
   follow-up? *(leaning yes — zero client change, no #5 dependency.)*
2. **Roster source:** v1 = optional manual CSV roster + went-dark; IdP/SCIM deferred to #7? *(leaning yes.)*
3. **Drill-down granularity (the #3 tension):** do coverage reports stop at **host/machine**, or may
   they drill down to a **user** (gated behind auditor + access-logged)? *(leaning host-default;
   user-drill-down only if auditor + logged — need your call.)*
4. **Alerting in v1** (webhook/email when a gap opens), or dashboard/endpoint only first? *(leaning
   endpoint/UI first; alerting follow-up.)*
5. **Thresholds:** fixed defaults (e.g. silent > max(3× median interval, 24h)) vs per-tenant
   configurable? *(leaning sensible defaults + config override.)*
