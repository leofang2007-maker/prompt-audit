import { useEffect, useState } from "react";
import {
  createAdmin, createTenant, deleteAdmin, deleteTenant, listAdmins, listTenants,
  OrgAdmin, rotateTenantToken, setAdminRole, TenantRow,
} from "../api";
import { TokenValue } from "./TokenValue";

/** Platform superadmin: create orgs, view/rotate their ingest tokens, manage their admins. */
export function TenantsPage() {
  const [rows, setRows] = useState<TenantRow[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [newName, setNewName] = useState("");
  const [openId, setOpenId] = useState<string | null>(null);

  async function load() {
    try { setRows(await listTenants()); setErr(null); } catch (e) { setErr((e as Error).message); }
  }
  useEffect(() => { load(); }, []);

  async function add() {
    const name = newName.trim();
    if (!name) return;
    try { await createTenant(name); setNewName(""); await load(); }
    catch (e) { setErr((e as Error).message); }
  }
  async function rotate(id: string) {
    if (!confirm("Rotate this org's token? Its current token stops working immediately.")) return;
    try { await rotateTenantToken(id); await load(); } catch (e) { setErr((e as Error).message); }
  }
  async function remove(t: TenantRow) {
    if (!confirm(`Delete org "${t.name}" and its ${t.admin_count} admin(s)? Its audit history stays but becomes unassigned.`)) return;
    try { await deleteTenant(t.id); await load(); } catch (e) { setErr((e as Error).message); }
  }

  return (
    <div className="panel-page">
      <h2>Organizations</h2>
      <p className="muted">Each org gets one ingest token (hand to its machines) and its own admins (who see only that org's audit log).</p>

      <div className="filter-actions" style={{ marginBottom: 16 }}>
        <input className="grow-input" placeholder="New organization name" value={newName}
               onChange={(e) => setNewName(e.target.value)} onKeyDown={(e) => e.key === "Enter" && add()} />
        <button className="btn primary" onClick={add}>Create organization</button>
      </div>
      {err && <div className="error" style={{ marginBottom: 12 }}>{err}</div>}

      <div className="table-wrap">
        <table className="grid">
          <thead>
            <tr><th>Organization</th><th>Ingest token</th><th>Admins</th><th>Created (UTC)</th><th></th></tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.id}>
                <td className="nowrap">{t.name}</td>
                <td><TokenValue token={t.token} /></td>
                <td>
                  <button className="btn ghost small" onClick={() => setOpenId(openId === t.id ? null : t.id)}>
                    {t.admin_count} admin{t.admin_count === 1 ? "" : "s"} {openId === t.id ? "▲" : "▼"}
                  </button>
                </td>
                <td className="mono nowrap">{fmt(t.created_at)}</td>
                <td className="nowrap">
                  <button className="btn ghost small" onClick={() => rotate(t.id)}>Rotate</button>{" "}
                  <button className="btn ghost small" onClick={() => remove(t)}>Delete</button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={5} className="empty">No organizations yet.</td></tr>}
          </tbody>
        </table>
      </div>

      {openId && <AdminManager tenantId={openId} onChanged={load} />}
    </div>
  );
}

function AdminManager({ tenantId, onChanged }: { tenantId: string; onChanged: () => void }) {
  const [admins, setAdmins] = useState<OrgAdmin[]>([]);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState<"viewer" | "auditor">("viewer");
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try { setAdmins(await listAdmins(tenantId)); setErr(null); } catch (e) { setErr((e as Error).message); }
  }
  useEffect(() => { load(); }, [tenantId]);

  async function add() {
    if (!email.trim() || !password) return;
    try { await createAdmin(tenantId, email.trim(), password, role); setEmail(""); setPassword(""); setRole("viewer"); await load(); onChanged(); }
    catch (e) { setErr((e as Error).message); }
  }
  async function changeRole(a: OrgAdmin, next: "viewer" | "auditor") {
    try { await setAdminRole(tenantId, a.id, next); await load(); } catch (e) { setErr((e as Error).message); }
  }
  async function remove(a: OrgAdmin) {
    if (!confirm(`Remove admin ${a.email}?`)) return;
    try { await deleteAdmin(tenantId, a.id); await load(); onChanged(); } catch (e) { setErr((e as Error).message); }
  }

  return (
    <div className="sub-panel">
      <h4>Org admins</h4>
      <p className="muted" style={{ marginTop: -4 }}>
        <strong>viewer</strong> sees metadata + redacted previews; <strong>auditor</strong> can reveal
        full prompt text &amp; export — every reveal is recorded in the access log.
      </p>
      {err && <div className="error">{err}</div>}
      <ul className="admin-list">
        {admins.map((a) => (
          <li key={a.id}>
            <span>{a.email}</span>
            <span className="admin-controls">
              <select className="role-select" value={a.role} onChange={(e) => changeRole(a, e.target.value as "viewer" | "auditor")}>
                <option value="viewer">viewer</option>
                <option value="auditor">auditor</option>
              </select>
              <button className="btn ghost small" onClick={() => remove(a)}>Remove</button>
            </span>
          </li>
        ))}
        {admins.length === 0 && <li className="muted">No admins yet — this org can't log in until you add one.</li>}
      </ul>
      <div className="filter-actions">
        <input placeholder="admin email" value={email} onChange={(e) => setEmail(e.target.value)} />
        <input type="password" placeholder="initial password" value={password} onChange={(e) => setPassword(e.target.value)} />
        <select className="role-select" value={role} onChange={(e) => setRole(e.target.value as "viewer" | "auditor")}>
          <option value="viewer">viewer</option>
          <option value="auditor">auditor</option>
        </select>
        <button className="btn" onClick={add}>Add admin</button>
      </div>
    </div>
  );
}

function fmt(iso: string | null): string {
  return iso ? iso.replace("T", " ").replace(/\.\d+Z$/, "Z").replace("Z", " UTC") : "—";
}
