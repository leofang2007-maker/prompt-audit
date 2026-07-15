# Prompt Audit

> Receive → store → audit the prompts developers send to AI coding tools.

Developers using AI coding tools (Qoder, Claude Code, …) run a small **client hook** that reports
every submitted prompt to this service. Compliance / security teams then browse, filter, inspect,
and export the audit log through a web console.

This is the **server side** (ingest + storage + audit UI) — a clean, minimal, open-source-friendly
demo meant to tell the story end to end, and to be extended into a full product later.

```
  AI coding tool (dev machine)                 Compliance / security team
        │ client hook                                    │ browser
        │ POST /api/v1/prompts                            │ https://audit.theprismatlas.com
        │ Authorization: INGEST_TOKEN  (write-only)       │ admin session (read-only)
        ▼                                                 ▼
              shared unified-ingress nginx  (also fronts prism & co.)
                                    │  audit.theprismatlas.com → 127.0.0.1:8091
                                    ▼
  ┌──────────────────  server (Spring Boot)  ──────────────────┐
  │  /  → SPA (React, baked into the jar)     /api/*  → API     │   ← 1 container
  └──────────────────────────────┬────────────────────────────┘
                                  ▼
                  shared MySQL  (dedicated `promptaudit` DB)
```

Same architecture as the PrismAtlas white-label platform (React + Vite SPA → Spring Boot control
plane → shared MySQL, shipped as an ACR image via docker-compose), trimmed to a single-admin demo:
**no multi-tenant, no RBAC, no SSO** — on purpose. And collapsed to **one container**: the server
serves both the SPA (the built React bundle is baked into the jar's static resources) and the API,
so there is no project nginx at all — the public entry reuses the existing shared nginx (one added
`server_name audit.theprismatlas.com` block).

## The security property (the headline)

Two **completely separate** authentication schemes, so a leaked write token can never read the log:

| Audience | Route | Auth | Can |
|---|---|---|---|
| Client hooks (every dev machine) | `POST /api/v1/prompts` | `INGEST_TOKEN` (shared bearer) | **write only** |
| Compliance / security team | `GET /api/v1/prompts[...]`, export | admin session JWT (login) | **read only** |

They never overlap — see [`server/.../auth/SecurityInterceptor.java`](server/src/main/java/com/gigrt/promptaudit/auth/SecurityInterceptor.java),
where the whole boundary lives in one readable place.

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/prompts` | ingest token | Report a prompt. Body: `{event_id?, timestamp, session_id, user_email, repo, branch, cwd, hostname, prompt}`. `prompt` required (else 400). → `200 {"ok":true,"id":"pr_…"}`. **Idempotent on `event_id`**: a repeat (IDE double-fire / drain retry) returns the original id with `"deduplicated":true` — no new row. Logs prompt **length** only — never the token or text. |
| `POST` | `/api/v1/auth/login` | — | Admin login (`{email,password}` from config) → `{token, profile}`. |
| `POST` | `/api/v1/auth/logout` | admin | Stateless acknowledge (client drops token). |
| `GET` | `/api/v1/prompts` | admin | Filtered, paginated list. Params: `from,to,user_email,repo,session_id,keyword,page,page_size`. Returns summaries + prompt preview + total. |
| `GET` | `/api/v1/prompts/{id}` | admin | Full record incl. complete prompt. |
| `GET` | `/api/v1/prompts/export?format=csv\|json` | admin | Export the current filter set as a download. |

## Data model

`id` · `event_id` (client idempotency key, UNIQUE) · `timestamp` (client event time, RFC3339 UTC) ·
`received_at` (server time) · `session_id` · `user_email` · `repo` · `branch` · `cwd` · `hostname` ·
`prompt` (full text) · `prompt_length`.
Indexed on `received_at`, `user_email`, `repo`, `session_id` for fast filtering; UNIQUE on `event_id`.

## Local development

Fastest loop — backend + frontend separately:

```bash
# terminal 1 — backend (H2 in-memory in tests; for a run, point DB_* at any MySQL)
cd server
DB_HOST=… DB_USER=… DB_PASSWORD=… ADMIN_PASSWORD=changeme INGEST_TOKEN=dev-token \
  mvn spring-boot:run              # :8080

# terminal 2 — frontend (vite proxies /api → :8080)
cd web && npm install && npm run dev   # http://localhost:5173
```

Full stack in one container (mirrors prod — server serves SPA + API on :8091):

```bash
cp .env.example .env    # fill DB/admin/ingest secrets
docker compose up --build     # http://localhost:8091
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
ops/      build.sh / deploy.sh / Jenkins job + nginx-prompt-audit.site (shared-nginx block)
examples/ client-hook reference (report_prompt.sh + Claude Code wiring)
```

## Configuration

All via env (see [`.env.example`](.env.example)): `DB_*` (shared MySQL, dedicated `promptaudit` DB),
`ADMIN_EMAIL` / `ADMIN_PASSWORD`, `JWT_SECRET`, `INGEST_TOKEN`.

## Deployment

host2 ECS + docker-compose (one container), image from ACR, built by Jenkins; public entry via the
shared nginx at `audit.theprismatlas.com` — see [`ops/README.md`](ops/README.md).

## Not in scope (deliberately)

Multi-tenant, complex RBAC, SSO. This is a demo: clear story, readable code, easy to extend.
