# Ops — prompt-audit

Deployment form: **host1 ECS + docker-compose** (same infra as PrismAtlas / the middle-end).

## CI/CD loop

```
push (Bitbucket AIW/prompt-audit)
   ├─> Jenkins dev-promptaudit-web     ── SERVICES=web    ops/build.sh ──> web image → ACR
   └─> Jenkins dev-promptaudit-server  ── SERVICES=server ops/build.sh ──> server image → ACR
   └─> host1: ops/deploy.sh  ── compose -f docker-compose.prod.yml pull && up -d ──> live
```

### 1) Import the Jenkins jobs (one-time)

```bash
curl -X POST -u admin:<pwd> -H "Content-Type: application/xml" \
  --data-binary @ops/jenkins-job-web.xml \
  "http://192.168.0.144:8080/createItem?name=dev-promptaudit-web"
curl -X POST -u admin:<pwd> -H "Content-Type: application/xml" \
  --data-binary @ops/jenkins-job-server.xml \
  "http://192.168.0.144:8080/createItem?name=dev-promptaudit-server"
```

Trigger with params `mbranch=master`, `mimageVersion=0.0.x`. Each job runs `ops/build.sh` with
`SERVICES` preset (the build node must already be `docker login`'d to ACR — bound via the `acr`
credential). Manual full build: `mimageVersion=0.0.2 ops/build.sh`.

### 2) Deploy on host1

```bash
git clone ssh://git@192.168.0.88:7999/AIW/prompt-audit.git /opt/prompt-audit
cd /opt/prompt-audit
cp .env.example .env      # fill DB_PASSWORD / ADMIN_PASSWORD / JWT_SECRET / INGEST_TOKEN
ops/deploy.sh             # = docker compose -f docker-compose.prod.yml pull && up -d
```

### One-time database setup (shared MySQL)

```sql
CREATE DATABASE IF NOT EXISTS promptaudit CHARACTER SET utf8mb4;
CREATE USER 'promptaudit'@'%' IDENTIFIED BY '<strong-password>';
GRANT ALL PRIVILEGES ON promptaudit.* TO 'promptaudit'@'%';
```

The server auto-creates its single table on first boot (`ddl-auto=update`). The `promptaudit`
database keeps audit data isolated from the RWS / middle-end schemas on the same MySQL server.

## Network

`docker-compose.prod.yml` uses one `edge` bridge network. The server reaches the shared MySQL via
the host DNS/PVTZ name `mysql.bedrock.internal`, so no shared docker network is required. Front it
with the host2 unified-ingress if you want a public hostname (see PrismAtlas `ops/nginx-*.site`).

## Key files

- `ops/build.sh` — build + push images (`SERVICES=web|server`), no hardcoded creds
- `ops/deploy.sh` — host1 pull + restart + health
- `ops/jenkins-job-web.xml` / `ops/jenkins-job-server.xml` — the two build jobs
- `docker-compose.prod.yml` + `.env.example` — production orchestration
