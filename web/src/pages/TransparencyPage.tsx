import { useEffect, useState } from "react";
import { getTransparency, Transparency } from "../api";

/**
 * Developer-facing disclosure (spec 0003). Reads the public /transparency endpoint and states plainly
 * what this deployment captures, that secrets are redacted, that admin access is itself logged, and
 * that no productivity scoring is computed — "enable, don't surveil."
 */
export function TransparencyPage() {
  const [t, setT] = useState<Transparency | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => { getTransparency().then(setT).catch((e) => setErr((e as Error).message)); }, []);

  if (err) return <div className="panel-page"><p className="error">{err}</p></div>;
  if (!t) return <div className="panel-page"><p className="muted">Loading…</p></div>;

  return (
    <div className="panel-page">
      <h2>Transparency</h2>
      <p className="login-sub">Exactly what this system records about you — and the limits it places on itself.</p>

      <div className="sub-panel">
        <h4>What is captured</h4>
        <div className="chips">{t.captured_fields.map((f) => <span className="chip" key={f}>{f}</span>)}</div>
      </div>

      <div className="sub-panel">
        <h4>Secret redaction {t.redaction.enabled ? "✅ on" : "⛔ off"}</h4>
        <p className="muted">Mode: <code>{t.redaction.mode}</code>. {t.redaction.note}</p>
      </div>

      <div className="sub-panel">
        <h4>Admin access is accountable</h4>
        <ul className="disclosure">
          <li>{t.admin_access.full_text_view_logged ? "✅" : "—"} Every full-text view is logged</li>
          <li>{t.admin_access.export_logged ? "✅" : "—"} Every export is logged</li>
          <li>{t.admin_access.reason_required ? "✅" : "—"} A reason is required to reveal full text</li>
          <li>{t.admin_access.tamper_evident ? "✅" : "—"} The access log is tamper-evident (hash-chained)</li>
        </ul>
        <p className="muted">{t.admin_access.note}</p>
      </div>

      <div className="sub-panel">
        <h4>Retention</h4>
        <p className="muted">{t.retention}</p>
      </div>

      <div className="sub-panel">
        <h4>No productivity scoring</h4>
        <p className="muted">{t.productivity_scoring}</p>
      </div>
    </div>
  );
}
