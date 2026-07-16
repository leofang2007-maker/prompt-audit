import { useCallback, useEffect, useState } from "react";
import { AccessEntry, getAccessIntegrity, Integrity, listAccessLog } from "../api";

const PAGE_SIZE = 20;

/**
 * "Who watched the watchers" (spec 0003). Every full-text view / export of prompt content — including
 * by the platform admin — is listed here with the reason given, and the log itself is tamper-evident.
 */
export function AccessLogPage() {
  const [items, setItems] = useState<AccessEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [integrity, setIntegrity] = useState<Integrity | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const r = await listAccessLog(page, PAGE_SIZE);
      setItems(r.items);
      setTotal(r.total);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { getAccessIntegrity().then(setIntegrity).catch(() => setIntegrity(null)); }, []);

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="audit">
      <div className="result-bar" style={{ marginTop: 0 }}>
        <h2 style={{ margin: 0, fontSize: 18 }}>Access log</h2>
        <span className="muted">Every reveal of full prompt text or export — with the reason given.</span>
      </div>

      {integrity && (
        <div className={"integrity " + (integrity.ok ? "ok" : "broken")}>
          <span>
            {integrity.ok
              ? "✅ Access log is intact — no access record has been altered or removed"
              : "⚠️ Access log integrity FAILED — an access record was tampered with"}
          </span>
        </div>
      )}

      <div className="result-bar">
        <span>{loading ? "Loading…" : `${total} access record${total === 1 ? "" : "s"}`}</span>
        {err && <span className="error">{err}</span>}
      </div>

      <div className="table-wrap">
        <table className="grid">
          <thead>
            <tr>
              <th>When (UTC)</th><th>Actor</th><th>Role</th><th>Action</th>
              <th>Target</th><th>Reason</th><th>IP</th>
            </tr>
          </thead>
          <tbody>
            {items.map((a) => (
              <tr key={a.id}>
                <td className="mono nowrap">{fmt(a.created_at)}</td>
                <td className="nowrap">{a.actor_email ?? "—"}</td>
                <td className="nowrap">
                  <span className={"role-badge " + (a.actor_role === "platform" ? "platform" : "auditor")}>
                    {a.actor_role ?? "—"}
                  </span>
                </td>
                <td className="nowrap">{a.action === "export" ? "📤 export" : "👁 view"}</td>
                <td className="mono nowrap">{a.target_record_id ?? (a.query_json ? "query" : "—")}</td>
                <td className="preview">{a.reason ?? "—"}</td>
                <td className="mono nowrap">{a.ip ?? "—"}</td>
              </tr>
            ))}
            {!loading && items.length === 0 && (
              <tr><td colSpan={7} className="empty">No access records yet.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="pager">
        <button className="btn ghost" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>← Prev</button>
        <span className="pageinfo">Page {page} / {totalPages}</span>
        <button className="btn ghost" disabled={page >= totalPages} onClick={() => setPage((p) => p + 1)}>Next →</button>
      </div>
    </div>
  );
}

function fmt(iso: string | null): string {
  if (!iso) return "—";
  return iso.replace("T", " ").replace(/\.\d+Z$/, "Z").replace("Z", " UTC");
}
