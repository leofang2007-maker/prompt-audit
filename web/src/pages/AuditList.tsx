import { useCallback, useEffect, useState } from "react";
import { exportUrl, Filters, listPrompts, Summary } from "../api";
import { Detail } from "./Detail";
import { IntegrityBanner } from "./IntegrityBanner";

const PAGE_SIZE = 20;
const EMPTY: Filters = {};

export function AuditList() {
  // `filters` is the draft in the form; `applied` is what's actually queried (on Search / page change).
  const [filters, setFilters] = useState<Filters>(EMPTY);
  const [applied, setApplied] = useState<Filters>(EMPTY);
  const [page, setPage] = useState(1);
  const [items, setItems] = useState<Summary[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const r = await listPrompts(applied, page, PAGE_SIZE);
      setItems(r.items);
      setTotal(r.total);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [applied, page]);

  useEffect(() => { load(); }, [load]);

  function search() { setPage(1); setApplied(filters); }
  function reset() { setFilters(EMPTY); setApplied(EMPTY); setPage(1); }

  const set = (k: keyof Filters) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setFilters((f) => ({ ...f, [k]: e.target.value }));

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="audit">
      <IntegrityBanner />
      <div className="filters">
        <div className="filter-grid">
          <label className="field"><span>From</span>
            <input type="datetime-local" value={filters.from ?? ""} onChange={set("from")} /></label>
          <label className="field"><span>To</span>
            <input type="datetime-local" value={filters.to ?? ""} onChange={set("to")} /></label>
          <label className="field"><span>User email</span>
            <input value={filters.user_email ?? ""} onChange={set("user_email")} placeholder="dev@acme.com" /></label>
          <label className="field"><span>Org ID</span>
            <input value={filters.org_id ?? ""} onChange={set("org_id")} placeholder="019f21f9-…" /></label>
          <label className="field"><span>User UID</span>
            <input value={filters.user_uid ?? ""} onChange={set("user_uid")} placeholder="019f25ea-…" /></label>
          <label className="field"><span>Repo</span>
            <input value={filters.repo ?? ""} onChange={set("repo")} placeholder="acme/api" /></label>
          <label className="field"><span>Session ID</span>
            <input value={filters.session_id ?? ""} onChange={set("session_id")} placeholder="sess-…" /></label>
          <label className="field grow"><span>Keyword (prompt text)</span>
            <input value={filters.keyword ?? ""} onChange={set("keyword")}
                   onKeyDown={(e) => e.key === "Enter" && search()} placeholder="search full prompt…" /></label>
        </div>
        <div className="filter-actions">
          <button className="btn primary" onClick={search}>Search</button>
          <button className="btn ghost" onClick={reset}>Reset</button>
          <span className="spacer" />
          <a className="btn" href={exportUrl(applied, "csv")}>Export CSV</a>
          <a className="btn" href={exportUrl(applied, "json")}>Export JSON</a>
        </div>
      </div>

      <div className="result-bar">
        <span>{loading ? "Loading…" : `${total} record${total === 1 ? "" : "s"}`}</span>
        {err && <span className="error">{err}</span>}
      </div>

      <div className="table-wrap">
        <table className="grid">
          <thead>
            <tr>
              <th>Received (UTC)</th><th>User</th><th>Name</th><th>Org</th><th>Repo</th><th>Branch</th>
              <th>Host</th><th>Len</th><th>Prompt preview</th>
            </tr>
          </thead>
          <tbody>
            {items.map((it) => (
              <tr key={it.id} className="row" onClick={() => setOpenId(it.id)}>
                <td className="mono nowrap">{fmt(it.received_at)}</td>
                <td className="nowrap">{it.user_email ?? "—"}</td>
                <td className="nowrap">{it.user_name ?? "—"}</td>
                <td className="nowrap">{it.org_name ?? "—"}</td>
                <td className="nowrap">{it.repo ?? "—"}</td>
                <td className="nowrap">{it.branch ?? "—"}</td>
                <td className="nowrap">{it.hostname ?? "—"}</td>
                <td className="mono">{it.prompt_length}</td>
                <td className="preview">
                  {it.redaction_count > 0 && (
                    <span className="redacted-badge" title={`Secrets masked at capture: ${it.redacted_types}`}>
                      🛡 {it.redaction_count} redacted
                    </span>
                  )}
                  {it.prompt_preview}
                </td>
              </tr>
            ))}
            {!loading && items.length === 0 && (
              <tr><td colSpan={9} className="empty">No matching prompts.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="pager">
        <button className="btn ghost" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>← Prev</button>
        <span className="pageinfo">Page {page} / {totalPages}</span>
        <button className="btn ghost" disabled={page >= totalPages} onClick={() => setPage((p) => p + 1)}>Next →</button>
      </div>

      {openId && <Detail id={openId} onClose={() => setOpenId(null)} />}
    </div>
  );
}

function fmt(iso: string | null): string {
  if (!iso) return "—";
  return iso.replace("T", " ").replace(/\.\d+Z$/, "Z").replace("Z", " UTC");
}
