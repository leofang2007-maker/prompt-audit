# Contributing

Thanks for your interest! This project is Apache-2.0 licensed. Contributions go through pull
requests — nobody merges to `main` without review + green CI.

## Workflow

1. **Fork** the repo (external contributors) or create a branch (collaborators).
2. Make your change on a topic branch (`fix/...`, `feat/...`).
3. Run the checks locally (see below) — they must pass.
4. Open a **pull request against `main`**. CI runs automatically.
5. A maintainer reviews. Once approved and CI is green, it gets merged (squash).

Direct pushes to `main` are not allowed — everything goes via PR.

## Run & test locally

```bash
# whole stack, zero external deps (bundles MySQL)
docker compose up --build            # → http://localhost:8091

# backend unit/integration tests (in-memory H2, no DB needed)
cd server && mvn test

# frontend type-check + build
cd web && npm install && npm run build
```

## Guidelines

- Keep PRs focused — one logical change per PR.
- Match the surrounding code style; the backend is plain Spring Boot (Java 8), the frontend is
  React + TypeScript.
- Add or update tests for behavior changes (`server/src/test/...`). CI must stay green.
- Don't commit secrets, real hostnames/IPs, or data. Config is env-driven (`.env`, never committed).
- The ingest contract (`POST /api/v1/prompts` fields) is a compatibility surface — discuss before
  changing it.

## Reporting issues

Use GitHub Issues. For anything security-sensitive, please contact the maintainer privately rather
than filing a public issue.
