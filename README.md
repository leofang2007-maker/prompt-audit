# Prompt Audit

[![CI](https://github.com/leofang2007-maker/prompt-audit/actions/workflows/ci.yml/badge.svg)](https://github.com/leofang2007-maker/prompt-audit/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> Receive → store → audit the prompts developers send to AI coding tools.

Developers using AI coding tools (Qoder, Claude Code, …) run a small **client hook** that reports
every submitted prompt to this service. Compliance / security teams then browse, filter, inspect,
and export the audit log through a web console.

![Prompt Audit — audit console](docs/audit-log.png)

*The audit console: filter by user / org / repo / keyword / time range, open any prompt in full,
and export CSV/JSON. Each org's admins see only their own org's prompts.*

This repo ships **both halves**: the **server** (ingest + storage + audit console) and the **Qoder
client plugin** ([`clients/qoder/`](clients/qoder/)) that captures each prompt and reports it.
Install the plugin, run the server — done. (Any tool with a pre-submit hook works; see
[`examples/`](examples/) for a generic reporter and Claude Code wiring.)

```
  AI coding tool (dev machine)                 Compliance / security team
        │ client hook                                    │ browser
        │ POST /api/v1/prompts                            │ your reverse proxy (TLS)
        │ Authorization: INGEST_TOKEN  (write-only)       │ admin session (read-only)
        ▼                                                 ▼
  ┌──────────────────  server (Spring Boot)  ──────────────────┐
  │  /  → SPA (React, baked into the jar)     /api/*  → API     │   ← 1 container
  └──────────────────────────────┬────────────────────────────┘
                                  ▼
                            MySQL  (`promptaudit` DB)
```

**One container**: the server serves both the SPA (the built React bundle is baked into the jar's
static resources) and the API — no separate web/nginx process. Put it behind your own TLS reverse
proxy. Multi-tenant: each org has its own ingest token + its own admin logins, with strict data
isolation between orgs.

## The security property (the headline)

Two **completely separate** authentication schemes, plus tenant isolation:

| Audience | Route | Auth | Can |
|---|---|---|---|
| An org's client hooks | `POST /api/v1/prompts` | that org's ingest token | **write only** |
| Org admin | `GET /api/v1/prompts[...]`, export | session JWT (role=org, tenant=X) | **read only — that org's rows** |
| Platform superadmin | + `/api/v1/tenants/*` | session JWT (role=platform) | manage orgs/tokens/admins; read all |

Two guarantees, both in [`server/.../auth/SecurityInterceptor.java`](server/src/main/java/com/gigrt/promptaudit/auth/SecurityInterceptor.java):
a leaked ingest token can't read a single record; and the owning org of a prompt is **derived from
the ingest token** (stamped as the trusted `tenant_org_id`), never from the client-claimed `org_id` —
so a machine can't forge its way into another org's data, and every admin read is filtered to its tenant.

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/prompts` | ingest token | Report a prompt. Body (v1.0.2): `{event_id?, timestamp, session_id, user_email, user_name, user_uid, org_id, org_name, repo, branch, cwd, transcript_path, hostname, prompt}`. Only `prompt` required (else 400); every other field is optional and stored as-is (fail-open, missing ⇒ null). → `200 {"ok":true,"id":"pr_…"}`. **Idempotent on `event_id`**: a repeat (IDE double-fire / drain retry) returns the original id with `"deduplicated":true` — no new row. Logs prompt **length** only — never the token or text. |
| `POST` | `/api/v1/auth/login` | — | Login (platform superadmin from env, or an org admin from DB) → `{token, profile{role,tenant,org_name}}`. |
| `POST` | `/api/v1/auth/logout` | admin | Stateless acknowledge (client drops token). |
| `GET` | `/api/v1/prompts` | admin | Filtered, paginated list — **isolated to the caller's tenant** (platform sees all). Params: `from,to,user_email,org_id,user_uid,repo,session_id,keyword,page,page_size`. |
| `GET` | `/api/v1/prompts/{id}` | admin | Full record (cross-tenant id ⇒ 404). |
| `GET` | `/api/v1/prompts/export?format=csv\|json` | admin | Export the current (tenant-scoped) filter set. |
| `GET`·`POST` | `/api/v1/tenants` · `/{id}/rotate-token` · `DELETE /{id}` | platform | List/create orgs, rotate/revoke their ingest token. |
| `GET`·`POST` | `/api/v1/tenants/{id}/admins` · `DELETE /{id}/admins/{aid}` | platform | Manage an org's admin logins. |
| `GET`·`POST` | `/api/v1/my/tenant` · `/tenant/rotate-token` | org admin | View / rotate **your own** org's ingest token. |

## Data model

`id` · `event_id` (idempotency key, UNIQUE) · `timestamp` (client event time, RFC3339 UTC) ·
`received_at` (server time) · `session_id` · `user_email` · `user_name` · `user_uid` · `org_id` ·
`org_name` · `repo` · `branch` · `cwd` · `transcript_path` (abs path on the reporting machine —
stored, never fetched) · `hostname` · `prompt` (full text) · `prompt_length` ·
`tenant_org_id` (TRUSTED owning org, from the ingest token — the isolation key).
Indexed on `received_at`, `user_email`, `org_id`, `user_uid`, `repo`, `session_id`, `tenant_org_id`;
UNIQUE on `event_id`. Plus `tenant` (org + its token) and `admin_user` (org logins, PBKDF2) tables.

## Quick start

One command, zero external dependencies (bundles its own MySQL):

```bash
docker compose up --build     # → http://localhost:8091
```

Defaults (override in a `.env`): admin `admin@promptaudit.local` / `changeme`, ingest token
`dev-ingest-token`. Log in, create an org under **Organizations**, and it gets its own ingest token.

## Local development

Faster iteration loop — backend + frontend separately:

```bash
# terminal 1 — backend (tests use in-memory H2; for a run, point DB_* at any MySQL)
cd server
DB_HOST=… DB_USER=… DB_PASSWORD=… ADMIN_PASSWORD=changeme INGEST_TOKEN=dev-token \
  mvn spring-boot:run              # :8080

# terminal 2 — frontend (vite proxies /api → :8080)
cd web && npm install && npm run dev   # http://localhost:5173
```

Try it:

```bash
# report a prompt (write side)
curl -X POST http://localhost:8091/api/v1/prompts \
  -H "Authorization: Bearer dev-ingest-token" -H "Content-Type: application/json" \
  -d '{"timestamp":"2026-07-15T10:00:00Z","user_email":"dev@acme.com","repo":"acme/api","prompt":"refactor the auth module"}'

# then log in at http://localhost:8091 as admin@promptaudit.local / <ADMIN_PASSWORD> and browse.
```

See [`examples/`](examples/) for wiring the client hook into Claude Code or any tool.

## Layout

```
server/   Spring Boot control plane — ingest + audit API + JWT/ingest auth   (plain Spring Boot, Java 8)
          server/Dockerfile builds web/ and bakes the SPA into the jar → one self-contained image
web/      React + Vite + TS audit console — login, list/filter, detail, export
clients/  client integrations — clients/qoder/ is the Qoder plugin (UserPromptSubmit hook that
          reports each prompt, + SessionStart drain-retry for offline queueing)
ops/      build.sh / deploy.sh / example reverse-proxy config for production
examples/ generic client-hook reference (report_prompt.sh + Claude Code wiring)
```

## Configuration

All via env (see [`.env.example`](.env.example)): `DB_*` (MySQL, dedicated `promptaudit` DB),
`ADMIN_EMAIL` / `ADMIN_PASSWORD` (platform superadmin bootstrap), `JWT_SECRET`, `INGEST_TOKEN`
(optional global/bootstrap token — each org normally gets its own from the Organizations page).

## Data & persistence

Two compose files, two storage models — pick per environment:

| Run | Compose | Database | Data across redeploys |
|-----|---------|----------|-----------------------|
| Local / evaluation | `docker-compose.yml` | **bundles** its own MySQL (named volume `promptaudit-db`) | persists in the volume; empty only on the very first `up` |
| Production | `docker-compose.prod.yml` | connects to **your external** MySQL (`DB_*`) | never touched — your rows live in your MySQL |

The schema is created/upgraded at startup by Hibernate (`ddl-auto=update`) from the JPA entities —
**additive only**: it adds missing tables/columns and never drops or wipes data. So **deploying a
new image does not reset the database** — existing rows survive upgrades. (No `.sql` migrations are
shipped; for stricter production change-control, add Flyway/Liquibase.)

Neither the repo nor the image contains a database or any data — only the app + the schema
definitions (the JPA entity classes). A database is attached at runtime via `DB_*`.

## Deployment

Build one image (`ops/build.sh`) and run it with `docker-compose.prod.yml` pointed at your MySQL,
behind your own TLS reverse proxy (example nginx block in `ops/nginx-prompt-audit.site`).
See [`ops/README.md`](ops/README.md).

## Roadmap / not yet built

SSO/SAML, fine-grained RBAC beyond the platform/org split, tamper-evident (hash-chained) storage,
and client-side reporting-coverage/gap detection. `ADMIN_EMAIL`/`ADMIN_PASSWORD` is the platform
superadmin bootstrap; org admins live in the DB and are created from the Organizations page.
