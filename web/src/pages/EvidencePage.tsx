import { useState } from "react";
import { Evidence, getEvidence } from "../api";

/**
 * Audit-ready evidence pack (spec 0007). An auditor picks a period, generates a tamper-anchored bundle
 * (integrity + access log + coverage + redaction + counts + config attestation — no raw prompt text),
 * views a printable report, and downloads the JSON.
 */
export function EvidencePage() {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [ev, setEv] = useState<Evidence | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function generate() {
    setBusy(true);
    setErr(null);
    try {
      setEv(await getEvidence(rfc(from), rfc(to)));
    } catch (e) {
      setErr((e as Error).message);
      setEv(null);
    } finally {
      setBusy(false);
    }
  }

  function download() {
    if (!ev) return;
    const blob = new Blob([JSON.stringify(ev, null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `evidence-${ev.period.from.slice(0, 10)}_${ev.period.to.slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  return (
    <div className="panel-page">
      <h2>Evidence</h2>
      <p className="login-sub">
        Generate a point-in-time, tamper-anchored compliance evidence pack. Summaries, counts, and hashes
        only — no raw prompt text. See the{" "}
        <a href="https://github.com/leofang2007-maker/prompt-audit/tree/main/docs/compliance" target="_blank" rel="noreferrer">
          SOC 2 / ISO 27001 control mappings
        </a>.
      </p>

      <div className="filter-actions" style={{ marginBottom: 16 }}>
        <label className="field"><span>From (default −90d)</span>
          <input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="field"><span>To (default now)</span>
          <input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} /></label>
        <button className="btn primary" disabled={busy} onClick={generate}>
          {busy ? "Generating…" : "Generate evidence"}
        </button>
        {ev && <button className="btn" onClick={download}>Download JSON</button>}
        {ev && <button className="btn ghost" onClick={() => window.print()}>Print / PDF</button>}
      </div>
      {err && <div className="error" style={{ marginBottom: 12 }}>{err}</div>}

      {ev && (
        <div className="evidence-report">
          <div className={"integrity " + (ev.integrity.ok && ev.access_log.chain_ok ? "ok" : "broken")}>
            <span>
              {ev.integrity.ok && ev.access_log.chain_ok
                ? "✅ Tamper-evident: audit log and access log both verify intact"
                : "⚠️ Integrity FAILED — a record was altered or removed (see integrity section)"}
            </span>
          </div>

          <div className="coverage-cards">
            <Card n={ev.records.total_in_period} label="prompts in period" />
            <Card n={ev.redaction.secrets_masked} label="secrets masked" warn={ev.redaction.secrets_masked > 0} />
            <Card n={ev.access_log.total} label="admin accesses" />
            <Card n={ev.coverage.went_dark} label="hosts went dark" warn={ev.coverage.went_dark > 0} />
          </div>

          <div className="meta-grid">
            <Meta label="Tenant" value={ev.tenant ?? "(all — platform)"} />
            <Meta label="Period" value={`${fmt(ev.period.from)} → ${fmt(ev.period.to)}`} />
            <Meta label="Generated" value={fmt(ev.generated_at)} />
            <Meta label="Records all-time" value={String(ev.records.total_all_time)} />
            <Meta label="Redaction mode" value={ev.config.redaction_mode} />
            <Meta label="Reason required" value={String(ev.config.reason_required_for_full_text)} />
            <Meta label="Retention" value={ev.config.retention} />
            <Meta label="Auth model" value={ev.config.auth_model} />
            <Meta label="Access views / exports" value={`${ev.access_log.by_action.view_detail ?? 0} / ${ev.access_log.by_action.export ?? 0}`} />
            <Meta label="Coverage (active / total)" value={`${ev.coverage.active_hosts} / ${ev.coverage.total_hosts}`} />
          </div>

          {ev.admins && ev.admins.length > 0 && (
            <div className="sub-panel">
              <h4>Admins &amp; roles</h4>
              <ul className="admin-list">
                {ev.admins.map((a) => (
                  <li key={a.email}><span>{a.email}</span><span className={"role-badge " + (a.role === "auditor" ? "auditor" : "")}>{a.role}</span></li>
                ))}
              </ul>
            </div>
          )}

          <div className="sub-panel">
            <h4>Tamper anchor</h4>
            <p className="muted">Bundle hash (re-running over unchanged data reproduces it):</p>
            <div className="token-value"><code>{ev.bundle_hash}</code></div>
          </div>
        </div>
      )}
    </div>
  );
}

function Card({ n, label, warn }: { n: number; label: string; warn?: boolean }) {
  return <div className={"cov-card" + (warn ? " warn" : "")}><div className="cov-num">{n}</div><div className="cov-label">{label}</div></div>;
}
function Meta({ label, value }: { label: string; value: string }) {
  return <div className="meta"><div className="meta-label">{label}</div><div className="meta-value">{value}</div></div>;
}
function fmt(iso: string): string { return iso ? iso.replace("T", " ").replace(/\.\d+Z$/, "Z").replace("Z", " UTC") : "—"; }
function rfc(local: string): string | undefined {
  if (!local) return undefined;
  const d = new Date(local);
  return isNaN(d.getTime()) ? undefined : d.toISOString();
}
