# 0008 — Native OIDC SSO + role mapping (RBAC)

- **Status:** Draft
- **Issue:** [#7](https://github.com/leofang2007-maker/prompt-audit/issues/7)
- **Author:** —
- **Created:** 2026-07-17

## Problem & motivation

Enterprises expect **SSO** — no separate passwords, central de-provisioning, MFA at the IdP. Today the
product has env platform-admin + DB org-admins (PBKDF2). #7 adds **native OIDC** (OAuth2
authorization-code flow) so admins sign in via Okta / Azure AD / Google / any OIDC IdP, plus the
**finer RBAC** that SSO requires: mapping an authenticated identity to a tenant + role, so users don't
need hand-created rows.

Built **from scratch in pure JDK** (RestTemplate + Jackson + `java.security`), consistent with the
project's hand-rolled JWT/PBKDF2 ethos — no Spring Security / OpenSAML heavy dependency.

## Goals / Non-goals

**Goals**
- OIDC authorization-code login: `…/auth/oidc/login` → IdP → `…/auth/oidc/callback` → app session JWT.
- **Proper id_token verification**: RS256 signature against the IdP JWKS + `iss` / `aud` / `exp` / `nonce`.
- **Role/tenant mapping (RBAC):** map the verified email → `{tenant, cap}` via explicit-email and
  email-domain rules, with a configured platform-admin allowlist; **default deny** (no match ⇒ no access).
- Coexist with existing password login (additive; both work).

**Non-goals**
- SAML (delegate to an OIDC-capable IdP, or a proxy — out of scope).
- SCIM auto-provisioning / directory sync (mapping rules are config, not a synced directory).
- Per-user DB rows for SSO users — sessions are provisioned from the mapping (no password, no row).

## Design

**Config (`app.oidc.*`):** `enabled`, `issuer` (discovery base), `client-id`, `client-secret`,
`redirect-uri`, `scopes` (default `openid email profile`), and mapping:
`platform-emails` (allowlist → platform), `email-roles` (explicit `email=tenant:cap`),
`domain-roles` (`domain=tenant:cap`), `default` (deny | a `tenant:cap`).

**Discovery:** on first use, GET `{issuer}/.well-known/openid-configuration` → `authorization_endpoint`,
`token_endpoint`, `jwks_uri`, `issuer` (cached).

**Login** — `GET /api/v1/auth/oidc/login`: build the authorize URL (`response_type=code`, `client_id`,
`redirect_uri`, `scope`, `state`, `nonce`) and 302 to the IdP. `state` is a short-lived **JWT** (reuse
`JwtUtil`) carrying the `nonce` — stateless CSRF protection, no server session store.

**Callback** — `GET /api/v1/auth/oidc/callback?code=&state=`:
1. Verify `state` JWT (signature + expiry) → recover `nonce`.
2. Exchange `code` at `token_endpoint` (server-to-server POST, `client_secret_basic`) → `id_token`.
3. **Verify `id_token`**: split JWT; match `kid` to a JWKS key; build the RSA public key from the JWK
   `n`/`e`; verify RS256 (`SHA256withRSA`); check `iss`==issuer, `aud`==client_id, `exp` not passed,
   `nonce`==recovered nonce.
4. Extract `email` (+ `name`). **Map** email → `{tenant, cap}` (platform-emails → platform; else
   explicit email rule; else domain rule; else `default`; no match ⇒ 403).
5. Mint the app session JWT (same claims as password login: `sub`, `role`, `cap`, `tenant`, `org_name`)
   and 302 to the SPA with the token (`/?sso=<jwt>`).

**SPA:** a "Sign in with SSO" button on the login page → `…/auth/oidc/login`. On load, if `?sso=<jwt>`
is present, store it, call `GET /api/v1/auth/me` to populate the profile, and clean the URL.

**New `GET /api/v1/auth/me`** (intercepted): returns the profile for the current session JWT (used by the
SSO redirect; also generally useful).

**JWKS RS256 (pure JDK):** fetch JWKS JSON; per key `RSAPublicKeySpec(new BigInteger(1, b64url(n)),
new BigInteger(1, b64url(e)))` → `KeyFactory("RSA")`; `Signature("SHA256withRSA")` over
`header.payload`. Cache JWKS; refresh on unknown `kid`.

## Security & privacy

- **id_token is fully verified** (signature + iss/aud/exp/nonce) — not merely decoded.
- **Default deny**: an authenticated IdP user with no mapping rule gets **no** access (403), so enabling
  SSO doesn't silently admit the whole directory.
- `state`/`nonce` prevent CSRF + replay; `client_secret` never leaves the server; code exchange is
  server-to-server over TLS.
- Platform access requires an **explicit email allowlist**, never a domain rule.
- Existing auth is unchanged; SSO is off unless `app.oidc.enabled=true` with a client configured.

## Edge cases & failure modes

- IdP down / discovery fails → login returns a clear error; password login still works.
- Unknown `kid` → refresh JWKS once, then fail closed.
- Clock skew → small `exp`/`iat` leeway.
- Email not verified / absent → deny.
- `state` expired/tampered → reject the callback.

## Acceptance criteria / test plan

1. `login` redirects (302) to the IdP authorize URL with `client_id`, `redirect_uri`, `state`, `nonce`.
2. `callback` with a valid signed id_token (test IdP / injected JWKS) mints a session mapped to the right
   `{tenant, cap}`; the session then works on the audit APIs, tenant-scoped.
3. A tampered/expired id_token or bad `state` → rejected.
4. An email with no mapping rule → 403 (default deny); a `platform-emails` match → platform role.
5. `GET /api/v1/auth/me` returns the profile for a valid token; password login is unaffected.

## Alternatives considered

- **Trusted reverse-proxy header SSO** — lighter (delegate to oauth2-proxy/Authelia), but the user chose
  native OIDC (no proxy dependency). Could be added later as an alternative.
- **Spring Security OAuth2 client** — pulls a large dependency tree; the from-scratch RP keeps the lean
  build (no internal-Nexus reliance) and matches the existing hand-rolled auth.
- **Skip id_token signature** (rely on the direct TLS token-endpoint channel — spec-permitted for code
  clients) — rejected; a compliance product should verify signatures.

## Migration / rollout

Additive: new `auth/oidc/*` + `/auth/me` endpoints, config, a login-page button. No schema change (SSO
sessions are mapping-provisioned, not stored). Off by default.

## Open questions

1. **id_token verification** — full JWKS RS256 (recommended) vs trust-direct-TLS? *(leaning full JWKS.)*
2. **Mapping config** — platform-email allowlist + explicit email rules + domain rules + `default`
   (deny by default)? *(leaning yes; default deny.)*
3. **Provisioning** — SSO sessions are mapping-only (no `admin_user` row, no password)? *(leaning yes;
   optionally write an audit row later.)*
4. **`/auth/me`** endpoint to hydrate the SPA profile after the SSO redirect? *(leaning yes.)*
5. **Token hand-off** — redirect to the SPA with the JWT in the query (`?sso=`) then clean the URL?
   *(leaning yes; simple and same-origin.)*
