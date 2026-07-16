# Ops — prompt-audit

Production form: **docker-compose, one container**, behind your own TLS reverse proxy, pointed at
your MySQL. The server image is self-contained (SPA baked into the Spring Boot jar).

## 1) Build the image

```bash
docker login <your-registry>
VERSION=0.0.1 IMAGE_REGISTRY=<your-registry> IMAGE_NAMESPACE=<you> ops/build.sh
```

`ops/build.sh` builds from the repo root with `server/Dockerfile` (multi-stage: builds the web/ SPA,
bakes it into the jar) and pushes `…/promptaudit-server:VERSION`. Wire this into whatever CI you use.

## 2) Prepare MySQL (one-time)

```sql
CREATE DATABASE IF NOT EXISTS promptaudit CHARACTER SET utf8mb4;
CREATE USER 'promptaudit'@'%' IDENTIFIED BY '<strong-password>';
GRANT ALL PRIVILEGES ON promptaudit.* TO 'promptaudit'@'%';
```

The server auto-creates its tables on first boot (`ddl-auto=update`). A dedicated `promptaudit`
database keeps audit data isolated from anything else on that MySQL server.

## 3) Deploy

```bash
git clone <this-repo> /opt/prompt-audit
cd /opt/prompt-audit
cp .env.example .env      # set IMAGE_*, DB_*, ADMIN_PASSWORD, JWT_SECRET, INGEST_TOKEN
ops/deploy.sh             # = docker compose -f docker-compose.prod.yml pull && up -d + health
```

The server publishes `127.0.0.1:${APP_PORT:-8091}` (localhost only). Point your reverse proxy at it.

## 4) Reverse proxy

`ops/nginx-prompt-audit.site` is an example nginx server block: terminate TLS at your proxy and
forward your audit hostname → `127.0.0.1:8091`. Any reverse proxy (nginx, Caddy, Traefik) works.

## Local run (no external MySQL)

For evaluation, `docker compose up --build` (root `docker-compose.yml`) bundles its own MySQL and
needs no `.env` — open `http://localhost:8091`.

## CI/CD with GitHub Actions + ghcr (recommended)

Two workflows ship in `.github/workflows/`:
- **`ci.yml`** — on every push/PR: `mvn test` + `npm run build`. No secrets.
- **`release.yml`** — on a `vX.Y.Z` tag (or manual dispatch): builds the image and pushes
  `ghcr.io/<owner>/promptaudit-server:X.Y.Z` (+ `:latest`). Auth uses the built-in `GITHUB_TOKEN`
  — nothing to configure. Public repo ⇒ public image ⇒ your host pulls it with no login.

Deploy is **pull-based** (a host with no public inbound can't be pushed to): on the host, point
`.env` at the image (`IMAGE_REGISTRY=ghcr.io`, `IMAGE_NAMESPACE=<owner>`, `TAG=X.Y.Z`) and run
`ops/deploy.sh`, or install `ops/poll-deploy.sh` on cron to auto-pull new tags.

## Key files

- `.github/workflows/` — CI (test/build) + release (build + push image to ghcr)
- `ops/build.sh` — build + push the single image manually (context = repo root, `server/Dockerfile`)
- `ops/deploy.sh` — pull + restart + health check on the host
- `ops/poll-deploy.sh` — optional cron: auto-pull the configured tag + restart on change
- `ops/nginx-prompt-audit.site` — example reverse-proxy server block
- `docker-compose.prod.yml` + `.env.example` — production orchestration (your MySQL, prebuilt image)
- `docker-compose.yml` — zero-dependency local run (bundled MySQL)
