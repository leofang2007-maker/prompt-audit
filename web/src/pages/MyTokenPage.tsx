import { useEffect, useState } from "react";
import { getMyTenant, MyTenant, rotateMyToken } from "../api";
import { TokenValue } from "./TokenValue";

/** Org-admin self-service: view + rotate this org's ingest token. */
export function MyTokenPage() {
  const [t, setT] = useState<MyTenant | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => { getMyTenant().then(setT).catch((e) => setErr((e as Error).message)); }, []);

  async function rotate() {
    if (!confirm("Rotate the ingest token? The current token stops working immediately — every machine in your org must be updated.")) return;
    setBusy(true);
    try { setT(await rotateMyToken()); } catch (e) { setErr((e as Error).message); } finally { setBusy(false); }
  }

  if (err) return <div className="error">{err}</div>;
  if (!t) return <div className="muted">Loading…</div>;

  const base = window.location.origin;
  return (
    <div className="panel-page">
      <h2>Ingest token — {t.name}</h2>
      <p className="muted">
        Machines in your org report prompts with this token. It is write-only: it can POST prompts but
        cannot read the audit log. Keep it secret; rotate it if it leaks or someone leaves.
      </p>

      <div className="token-card">
        <div className="token-row">
          <span className="meta-label">Token</span>
          <TokenValue token={t.token} />
        </div>
        <div className="token-row">
          <span className="meta-label">Created</span>
          <span className="mono">{t.created_at ?? "—"}</span>
        </div>
        <div className="token-row">
          <button className="btn" disabled={busy} onClick={rotate}>{busy ? "Rotating…" : "Rotate token"}</button>
        </div>
      </div>

      <h3>Report a prompt</h3>
      <pre className="prompt-body">{`curl -X POST ${base}/api/v1/prompts \\
  -H "Authorization: Bearer <YOUR_TOKEN>" \\
  -H "Content-Type: application/json" \\
  -d '{"prompt":"...","user_email":"dev@yourco.com"}'`}</pre>
    </div>
  );
}
