# Ops — prompt-audit

Deployment form: **host2 ECS + docker-compose** (app-layer host that also runs the shared ingress
nginx), same infra as PrismAtlas / the middle-end. **One container**, one image, one Jenkins job.

## CI/CD loop

```
push (Bitbucket AIW/prompt-audit)
   └─> Jenkins dev-promptaudit  ── ops/build.sh ──> promptaudit-server image → ACR
       (server/Dockerfile builds the SPA + bakes it into the Spring Boot jar)
   └─> host2: ops/deploy.sh  ── compose -f docker-compose.prod.yml pull && up -d ──> live
```

### 1) Import the Jenkins job (one-time)

```bash
curl -X POST -u admin:<pwd> -H "Content-Type: application/xml" \
  --data-binary @ops/jenkins-job.xml \
  "http://192.168.0.144:8080/createItem?name=dev-promptaudit"
```

Trigger with params `mbranch=master`, `mimageVersion=0.0.x`. The job runs `ops/build.sh` (the build
node must already be `docker login`'d to ACR — bound via the `acr` credential). Manual build:
`mimageVersion=0.0.2 ops/build.sh`.

### 2) Deploy on host2 (192.168.0.99 — app-layer host + shared ingress nginx)

```bash
git clone ssh://git@192.168.0.88:7999/AIW/prompt-audit.git /opt/prompt-audit
cd /opt/prompt-audit
cp .env.example .env      # fill DB_PASSWORD / ADMIN_PASSWORD / JWT_SECRET / INGEST_TOKEN
ops/deploy.sh             # = docker compose -f docker-compose.prod.yml pull && up -d
```

### 3) Wire into the shared nginx (one-time)

```bash
sudo cp ops/nginx-prompt-audit.site /etc/nginx/sites-available/prompt-audit
sudo ln -sf /etc/nginx/sites-available/prompt-audit /etc/nginx/sites-enabled/prompt-audit
sudo nginx -t && sudo nginx -s reload
```

DNS `A audit.theprismatlas.com → 8.219.193.199` (shared NAT EIP, same as www.theprismatlas.com).
This reuses the SAME nginx already fronting prism & the other services — prompt-audit runs NO nginx
of its own; the server container just publishes `127.0.0.1:${APP_PORT:-8091}`.

### One-time database setup (shared MySQL)

```sql
CREATE DATABASE IF NOT EXISTS promptaudit CHARACTER SET utf8mb4;
CREATE USER 'promptaudit'@'%' IDENTIFIED BY '<strong-password>';
GRANT ALL PRIVILEGES ON promptaudit.* TO 'promptaudit'@'%';
```

The server auto-creates its single table on first boot (`ddl-auto=update`). The `promptaudit`
database keeps audit data isolated from the RWS / middle-end schemas on the same MySQL server.

## Network

**One container.** The server (Spring Boot) serves both the SPA (baked into the jar) and the API,
and publishes `127.0.0.1:${APP_PORT:-8091}`. It reaches the shared MySQL via the host DNS/PVTZ name
`mysql.bedrock.internal`. Public entry = the shared unified-ingress nginx
(`audit.theprismatlas.com` → `127.0.0.1:8091`), the same nginx already fronting prism & co.

## Key files

- `ops/build.sh` — build + push the single image (context = repo root, `server/Dockerfile`)
- `ops/deploy.sh` — host2 pull + restart + health
- `ops/nginx-prompt-audit.site` — server block to add to the shared ingress nginx
- `ops/jenkins-job.xml` — the build job (`dev-promptaudit`)
- `docker-compose.prod.yml` + `.env.example` — production orchestration
