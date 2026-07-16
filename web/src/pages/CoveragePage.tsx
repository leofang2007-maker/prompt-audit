import { useCallback, useEffect, useState } from "react";
import { Coverage, getCoverage, getRoster, setRoster } from "../api";

/**
 * Reporting-coverage / gap detection (spec 0004). Turns "we captured N prompts" into "…and these hosts
 * went dark / never reported." Host-granular by design — this is the control's health, not who's quiet.
 */
export function CoveragePage() {
  const [cov, setCov] = useState<Coverage | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [roster, setRosterText] = useState("");
  const [savedMsg, setSavedMsg] = useState<string | null>(null);

  const load = useCallback(async () => {
    try { setCov(await getCoverage()); setErr(null); } catch (e) { setErr((e as Error).message); }
  }, []);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { getRoster().then((r) => setRosterText(r.join("\n"))).catch(() => {}); }, []);

  async function saveRoster() {
    const entities = roster.split("\n").map((s) => s.trim()).filter(Boolean);
    try {
      const n = await setRoster(entities);
      setSavedMsg(`Saved ${n} expected host${n === 1 ? "" : "s"}.`);
      setTimeout(() => setSavedMsg(null), 2500);
      await load();
    } catch (e) { setErr((e as Error).message); }
  }

  if (err) return <div className="panel-page"><p className="error">{err}</p></div>;
  if (!cov) return <div className="panel-page"><p className="muted">Loading…</p></div>;

  const wentDark = cov.silent.length;
  const never = cov.never_reported.length;

  return (
    <div className="panel-page">
      <h2>Coverage</h2>
      <p className="login-sub">Is the control actually working everywhere it should? Absence of evidence, made explicit.</p>

      <div className="coverage-cards">
        <div className="cov-card"><div className="cov-num">{cov.active_hosts}</div><div className="cov-label">active hosts</div></div>
        <div className={"cov-card" + (wentDark ? " warn" : "")}><div className="cov-num">{wentDark}</div><div className="cov-label">went dark</div></div>
        <div className={"cov-card" + (never ? " warn" : "")}><div className="cov-num">{never}</div><div className="cov-label">never reported</div></div>
        <div className="cov-card"><div className="cov-num">{cov.total_hosts}</div><div className="cov-label">hosts seen</div></div>
      </div>

      <div className="sub-panel">
        <h4>Went dark <span className="muted">— reported before, now silent past threshold</span></h4>
        {wentDark === 0 ? <p className="muted">No hosts have gone dark.</p> : (
          <table className="grid">
            <thead><tr><th>Host</th><th>Last seen (UTC)</th><th>Silent for</th><th>Usual interval</th><th>Reports</th></tr></thead>
            <tbody>
              {cov.silent.map((s) => (
                <tr key={s.entity}>
                  <td className="mono nowrap">{s.entity}</td>
                  <td className="mono nowrap">{fmt(s.last_seen)}</td>
                  <td className="nowrap">{dur(s.silent_for_sec)}</td>
                  <td className="nowrap">{dur(s.expected_interval_sec)}</td>
                  <td className="mono">{s.report_count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="sub-panel">
        <h4>Never reported <span className="muted">— on the expected roster, never seen</span></h4>
        {cov.roster_size === 0 ? (
          <p className="muted">No roster set. Without one, only cadence signals exist — a host that never
            reported and isn't on a roster is invisible. Add expected hosts below to catch shadow AI.</p>
        ) : never === 0 ? <p className="muted">Every rostered host has reported.</p> : (
          <div className="chips">{cov.never_reported.map((n) => <span className="chip warn" key={n.entity}>{n.entity}</span>)}</div>
        )}
      </div>

      <div className="sub-panel">
        <h4>Expected host roster</h4>
        <p className="muted" style={{ marginTop: -4 }}>One hostname per line — the machines you expect to be reporting.</p>
        <textarea className="roster-input" rows={5} value={roster} onChange={(e) => setRosterText(e.target.value)}
                  placeholder={"web-01.corp\nlaptop-jane.corp"} />
        <div className="filter-actions">
          <button className="btn primary" onClick={saveRoster}>Save roster</button>
          {savedMsg && <span className="muted">{savedMsg}</span>}
        </div>
      </div>

      <p className="muted" style={{ fontSize: 12 }}>
        Thresholds: silent past max({cov.thresholds.silent_multiplier}× a host's average interval,{" "}
        {cov.thresholds.floor_hours}h), after ≥ {cov.thresholds.min_history} reports.
      </p>
    </div>
  );
}

function fmt(iso: string | null): string {
  if (!iso) return "—";
  return iso.replace("T", " ").replace(/\.\d+Z$/, "Z").replace("Z", " UTC");
}

/** Humanize a duration in seconds → "3d 4h" / "5h" / "12m". */
function dur(sec: number): string {
  if (sec == null) return "—";
  const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}
