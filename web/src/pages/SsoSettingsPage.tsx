import { useCallback, useEffect, useState } from "react";
import { getOidcConfig, OidcConfig } from "../api";

/**
 * Platform-admin SSO page (spec 0008). Deliberately status + self-test + config generator — it does NOT
 * store secrets in the DB or make auth runtime-mutable (that would move the client secret out of the
 * environment and open a repoint/takeover vector). SSO is configured via `OIDC_*` env vars; this page
 * shows the live status, tests the IdP connection, and generates the .env snippet to paste.
 */
export function SsoSettingsPage() {
  const [cfg, setCfg] = useState<OidcConfig | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setBusy(true);
    try { setCfg(await getOidcConfig()); setErr(null); }
    catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const callbackUrl = `${window.location.origin}/api/v1/auth/oidc/callback`;

  return (
    <div className="panel-page">
      <h2>SSO (OIDC)</h2>
      <p className="login-sub">
        Sign-in via your IdP (Okta / Azure AD / Google / any OIDC). Configured via <code>OIDC_*</code>{" "}
        environment variables — this page shows status, tests the connection, and builds the config. The
        client secret stays in your environment (never stored here).
      </p>

      {err && <div className="error" style={{ marginBottom: 12 }}>{err}</div>}

      {cfg && (
        <>
          <div className={"integrity " + (cfg.config.usable ? "ok" : "broken")}>
            <span>
              {cfg.config.usable
                ? "✅ SSO is enabled and configured"
                : "⛔ SSO is not active — set the OIDC_* env vars (generator below) and redeploy"}
            </span>
            <button className="btn ghost small" disabled={busy} onClick={load}>{busy ? "testing…" : "Re-test"}</button>
          </div>

          <div className="sub-panel">
            <h4>Register this redirect URI at your IdP</h4>
            <div className="token-value"><code>{callbackUrl}</code></div>
          </div>

          <div className="meta-grid">
            <Meta label="Enabled flag" value={String(cfg.config.enabled_flag)} />
            <Meta label="Issuer" value={cfg.config.issuer || "—"} />
            <Meta label="Client ID" value={cfg.config.client_id || "—"} />
            <Meta label="Client secret" value={cfg.config.client_secret_set ? "set ✓" : "not set"} />
            <Meta label="Redirect URI" value={cfg.config.redirect_uri || "—"} />
            <Meta label="Scopes" value={cfg.config.scopes} />
            <Meta label="Default (unmapped users)" value={cfg.config.default} />
            <Meta label="Platform admins" value={cfg.config.platform_emails.join(", ") || "—"} />
          </div>

          <div className="sub-panel">
            <h4>Connection test <span className="muted">— live discovery probe</span></h4>
            {cfg.config.issuer ? (
              cfg.discovery.ok ? (
                <ul className="disclosure">
                  <li>✅ Discovery reachable</li>
                  <li className="mono">authorization: {cfg.discovery.authorization_endpoint}</li>
                  <li className="mono">token: {cfg.discovery.token_endpoint}</li>
                  <li className="mono">jwks: {cfg.discovery.jwks_uri}</li>
                </ul>
              ) : <p className="error">Discovery failed: {cfg.discovery.error ?? "unknown error"}</p>
            ) : <p className="muted">Set an issuer to test the connection.</p>}
          </div>

          {(Object.keys(cfg.config.email_roles).length > 0 || Object.keys(cfg.config.domain_roles).length > 0) && (
            <div className="sub-panel">
              <h4>Role mapping <span className="muted">(email/domain → tenant:role; unmapped ⇒ {cfg.config.default})</span></h4>
              <ul className="admin-list">
                {Object.entries(cfg.config.email_roles).map(([k, v]) => <li key={k}><span className="mono">{k}</span><span className="mono">{v}</span></li>)}
                {Object.entries(cfg.config.domain_roles).map(([k, v]) => <li key={k}><span className="mono">@{k}</span><span className="mono">{v}</span></li>)}
              </ul>
            </div>
          )}
        </>
      )}

      <Generator callbackUrl={callbackUrl} />
    </div>
  );
}

/** Client-side .env builder — nothing is sent to the server; the secret only enters the local text. */
function Generator({ callbackUrl }: { callbackUrl: string }) {
  const [f, setF] = useState({
    issuer: "", clientId: "", clientSecret: "", redirectUri: callbackUrl, scopes: "openid email profile",
    platformEmails: "", emailRoles: "", domainRoles: "", def: "deny",
  });
  const [copied, setCopied] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });

  const lines = [
    "OIDC_ENABLED=true",
    `OIDC_ISSUER=${f.issuer}`,
    `OIDC_CLIENT_ID=${f.clientId}`,
    `OIDC_CLIENT_SECRET=${f.clientSecret}`,
    `OIDC_REDIRECT_URI=${f.redirectUri}`,
    `OIDC_SCOPES=${f.scopes}`,
    f.platformEmails ? `OIDC_PLATFORM_EMAILS=${f.platformEmails}` : null,
    f.emailRoles ? `OIDC_EMAIL_ROLES=${f.emailRoles}` : null,
    f.domainRoles ? `OIDC_DOMAIN_ROLES=${f.domainRoles}` : null,
    `OIDC_DEFAULT=${f.def}`,
  ].filter(Boolean).join("\n");

  async function copy() {
    await navigator.clipboard.writeText(lines);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }

  return (
    <div className="sub-panel">
      <h4>Config generator <span className="muted">— builds the .env snippet (nothing is sent to the server)</span></h4>
      <div className="filter-grid">
        <label className="field grow"><span>Issuer</span><input value={f.issuer} onChange={set("issuer")} placeholder="https://accounts.google.com" /></label>
        <label className="field"><span>Client ID</span><input value={f.clientId} onChange={set("clientId")} /></label>
        <label className="field"><span>Client secret</span><input type="password" value={f.clientSecret} onChange={set("clientSecret")} /></label>
        <label className="field grow"><span>Redirect URI</span><input value={f.redirectUri} onChange={set("redirectUri")} /></label>
        <label className="field"><span>Scopes</span><input value={f.scopes} onChange={set("scopes")} /></label>
        <label className="field"><span>Default (unmapped)</span><input value={f.def} onChange={set("def")} placeholder="deny" /></label>
        <label className="field grow"><span>Platform admin emails (comma)</span><input value={f.platformEmails} onChange={set("platformEmails")} placeholder="you@company.com" /></label>
        <label className="field grow"><span>Email roles (email=org_id:role, comma)</span><input value={f.emailRoles} onChange={set("emailRoles")} placeholder="dev@acme.com=org_abc:auditor" /></label>
        <label className="field grow"><span>Domain roles (domain=org_id:role, comma)</span><input value={f.domainRoles} onChange={set("domainRoles")} placeholder="acme.com=org_abc:viewer" /></label>
      </div>
      <div className="prompt-head" style={{ marginTop: 12 }}>
        <span>.env snippet</span>
        <button className="btn ghost small" onClick={copy}>{copied ? "Copied ✓" : "Copy"}</button>
      </div>
      <pre className="prompt-body">{lines}</pre>
      <p className="muted" style={{ fontSize: 12 }}>
        Paste into your deploy environment (e.g. host <code>.env</code>) and redeploy. <code>org_id</code> is the
        tenant id from Organizations. Default-deny: users with no matching rule get no access.
      </p>
    </div>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return <div className="meta"><div className="meta-label">{label}</div><div className="meta-value">{value}</div></div>;
}
