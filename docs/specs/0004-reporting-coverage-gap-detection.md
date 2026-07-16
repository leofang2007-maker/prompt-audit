# 0004 — Reporting-coverage / gap detection

- **Status:** Accepted
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

## Decisions (resolved 2026-07-16)

1. **Cadence-only, server-side, in v1.** Coverage is derived from existing `prompt_record` data; a
   client-side heartbeat is a follow-up (no client change, no #5 dependency now).
2. **Optional manual roster in v1** (an admin sets the tenant's expected host list) enabling the
   *never-reported* signal; IdP/SCIM roster sync is deferred to #7.
3. **Host/machine granularity only — no user drill-down (the load-bearing decision).** The coverage
   unit is `hostname`: "is the control working on this machine." There is deliberately **no** per-user
   coverage view in v1, so gap detection cannot become "name-and-shame quiet developers" (#3). If a
   user-level view is ever added, it must be auditor-gated + access-logged — but that's out of scope here.
4. **Endpoint + UI first; no alerting in v1** (webhook/email when a gap opens is a follow-up).
5. **Sensible defaults + per-tenant config override.** Default went-dark = silent for >
   `max(3 × avg interval, 24h)`, and a host needs a minimum history (≥ 5 reports) before it can be
   flagged, so new/low-volume hosts aren't false-flagged.

**v1 signals:** *went-dark* (cadence) + *never-reported* (roster) + an *active host* headline. *Stale-client*
(needs client version from #5) and *volume-drop* are noted follow-ups. **Honest limit** (stated in the
UI): without a roster, only cadence signals exist; a machine that never reported and isn't on a roster is
invisible — coverage narrows the blind spot, it doesn't eliminate it.
