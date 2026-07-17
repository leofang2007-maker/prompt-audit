// Typed wrappers over the admin audit API. All requests carry the admin JWT (authedFetch).
import { authedFetch, getToken } from "./auth";

export interface Filters {
  from?: string;         // datetime-local value (converted to RFC3339 UTC before sending)
  to?: string;
  user_email?: string;
  org_id?: string;
  user_uid?: string;
  repo?: string;
  session_id?: string;
  keyword?: string;
}

export interface Summary {
  id: string;
  timestamp: string | null;
  received_at: string | null;
  session_id: string | null;
  user_email: string | null;
  user_name: string | null;
  org_name: string | null;
  repo: string | null;
  branch: string | null;
  hostname: string | null;
  prompt_length: number;
  redaction_count: number;
  redacted_types: string | null;
  prompt_preview: string;
}

export interface Detail extends Summary {
  event_id: string | null;
  user_uid: string | null;
  org_id: string | null;
  cwd: string | null;
  transcript_path: string | null;
  prompt: string | null;
  prompt_hidden?: boolean;   // spec 0003 — true when withheld from a viewer-role admin
}

export interface ListResult {
  items: Summary[];
  total: number;
  page: number;
  page_size: number;
}

/** A datetime-local input has no timezone; treat it as local wall-clock → RFC3339 UTC. */
function toRfc3339(local?: string): string | undefined {
  if (!local) return undefined;
  const d = new Date(local);
  return isNaN(d.getTime()) ? undefined : d.toISOString();
}

function query(f: Filters, extra: Record<string, string | number> = {}): string {
  const p = new URLSearchParams();
  const from = toRfc3339(f.from);
  const to = toRfc3339(f.to);
  if (from) p.set("from", from);
  if (to) p.set("to", to);
  if (f.user_email) p.set("user_email", f.user_email);
  if (f.org_id) p.set("org_id", f.org_id);
  if (f.user_uid) p.set("user_uid", f.user_uid);
  if (f.repo) p.set("repo", f.repo);
  if (f.session_id) p.set("session_id", f.session_id);
  if (f.keyword) p.set("keyword", f.keyword);
  for (const [k, v] of Object.entries(extra)) p.set(k, String(v));
  return p.toString();
}

export async function listPrompts(f: Filters, page: number, pageSize: number): Promise<ListResult> {
  const r = await authedFetch(`/api/v1/prompts?${query(f, { page, page_size: pageSize })}`);
  if (!r.ok) throw new Error(`list failed: ${r.status}`);
  return r.json();
}

/** Full record. `reason` is required to reveal full text as an auditor/platform admin (spec 0003);
 *  a viewer omits it and receives a masked record (prompt_hidden). 400 ⇒ reason required. */
export async function getPrompt(id: string, reason?: string): Promise<Detail> {
  const extra = reason ? `?reason=${encodeURIComponent(reason)}` : "";
  const r = await authedFetch(`/api/v1/prompts/${encodeURIComponent(id)}${extra}`);
  if (r.status === 400) throw new Error("reason_required");
  if (!r.ok) throw new Error(`detail failed: ${r.status}`);
  return r.json();
}

/** Export URL carries the token in the query so a plain browser navigation (download) is authorized.
 *  `reason` is recorded in the access log (spec 0003). */
export function exportUrl(f: Filters, format: "csv" | "json", reason: string): string {
  const token = getToken() ?? "";
  return `/api/v1/prompts/export?${query(f, { format, token, reason })}`;
}

// ---- tamper-evident chain integrity ----

export interface IntegrityChain {
  chain: string;
  ok: boolean;
  checked: number;
  unchained: number;
  head_hash: string;
  first_broken_id: string | null;
}
export interface Integrity {
  ok: boolean;
  chains: IntegrityChain[];
}

export async function getIntegrity(): Promise<Integrity> {
  const r = await authedFetch("/api/v1/integrity");
  if (!r.ok) throw new Error(`integrity check failed: ${r.status}`);
  return r.json();
}

// ---- admin access log (spec 0003) ----

export interface AccessEntry {
  id: string;
  created_at: string | null;
  actor_email: string | null;
  actor_role: string | null;   // "platform" | "auditor"
  action: string;              // "view_detail" | "export"
  target_record_id: string | null;
  query_json: string | null;
  reason: string | null;
  ip: string | null;
  tenant_org_id: string | null;
}
export interface AccessLogResult { items: AccessEntry[]; total: number; page: number; page_size: number; }

export async function listAccessLog(page: number, pageSize: number): Promise<AccessLogResult> {
  const r = await authedFetch(`/api/v1/access-log?page=${page}&page_size=${pageSize}`);
  if (!r.ok) throw new Error(`access log failed: ${r.status}`);
  return r.json();
}
export async function getAccessIntegrity(): Promise<Integrity> {
  const r = await authedFetch("/api/v1/access-log/integrity");
  if (!r.ok) throw new Error(`access integrity failed: ${r.status}`);
  return r.json();
}

// ---- reporting-coverage / gap detection (spec 0004) ----

export interface SilentHost {
  entity: string;
  kind: string;
  last_seen: string;
  report_count: number;
  expected_interval_sec: number;
  silent_for_sec: number;
}
export interface NeverReported { entity: string; kind: string; }
export interface Coverage {
  generated_at: string;
  total_hosts: number;
  active_hosts: number;
  silent: SilentHost[];
  never_reported: NeverReported[];
  roster_size: number;
  thresholds: { silent_multiplier: number; floor_hours: number; min_history: number };
}

export async function getCoverage(): Promise<Coverage> {
  const r = await authedFetch("/api/v1/coverage");
  if (!r.ok) throw new Error(`coverage failed: ${r.status}`);
  return r.json();
}
export async function getRoster(): Promise<string[]> {
  const r = await authedFetch("/api/v1/coverage/roster");
  if (!r.ok) throw new Error(`roster failed: ${r.status}`);
  return (await r.json()).entities ?? [];
}
export async function setRoster(entities: string[]): Promise<number> {
  const r = await authedFetch("/api/v1/coverage/roster", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ entities }),
  });
  if (!r.ok) throw new Error(`set roster failed: ${r.status}`);
  return (await r.json()).size ?? 0;
}

// ---- audit-ready evidence pack (spec 0007) ----

export interface Evidence {
  generated_at: string;
  period: { from: string; to: string };
  tenant: string | null;
  integrity: { ok: boolean; chains: unknown[] };
  access_log: { total: number; by_action: Record<string, number>; chain_ok: boolean; head_hashes: string[] };
  coverage: { total_hosts: number; active_hosts: number; went_dark: number; never_reported: number };
  redaction: { records_with_redactions: number; secrets_masked: number; by_type: Record<string, number> };
  records: { total_in_period: number; total_all_time: number };
  config: {
    redaction_mode: string; redaction_enabled: boolean; reason_required_for_full_text: boolean;
    retention: string; auth_model: string; roles: string[];
  };
  admins?: { email: string; role: string }[];
  bundle_hash: string;
}

export async function getEvidence(from?: string, to?: string): Promise<Evidence> {
  const p = new URLSearchParams();
  if (from) p.set("from", from);
  if (to) p.set("to", to);
  const r = await authedFetch(`/api/v1/evidence?${p.toString()}`);
  if (r.status === 403) throw new Error("Evidence requires the auditor role.");
  if (!r.ok) throw new Error(`evidence failed: ${r.status}`);
  return r.json();
}

// ---- transparency disclosure (spec 0003, public) ----

export interface Transparency {
  captured_fields: string[];
  redaction: { enabled: boolean; mode: string; note: string };
  admin_access: {
    full_text_view_logged: boolean;
    export_logged: boolean;
    reason_required: boolean;
    tamper_evident: boolean;
    note: string;
  };
  retention: string;
  productivity_scoring: string;
  roles: string[];
}

export async function getTransparency(): Promise<Transparency> {
  const r = await fetch("/api/v1/transparency");   // public — no auth
  if (!r.ok) throw new Error(`transparency failed: ${r.status}`);
  return r.json();
}

// ---- multi-tenant management ----

export interface TenantRow {
  id: string;
  name: string;
  token: string;
  admin_count: number;
  created_at: string | null;
}

export interface OrgAdmin {
  id: string;
  email: string;
  role: "viewer" | "auditor";   // spec 0003
  created_at: string | null;
}

async function jsonOrThrow(r: Response): Promise<any> {
  const body = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(body.error || `HTTP ${r.status}`);
  return body;
}

// platform superadmin
export async function listTenants(): Promise<TenantRow[]> {
  return (await jsonOrThrow(await authedFetch("/api/v1/tenants"))).items;
}
export async function createTenant(name: string): Promise<TenantRow> {
  return jsonOrThrow(await authedFetch("/api/v1/tenants", {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ name }),
  }));
}
export async function rotateTenantToken(id: string): Promise<TenantRow> {
  return jsonOrThrow(await authedFetch(`/api/v1/tenants/${id}/rotate-token`, { method: "POST" }));
}
export async function deleteTenant(id: string): Promise<void> {
  await jsonOrThrow(await authedFetch(`/api/v1/tenants/${id}`, { method: "DELETE" }));
}
export async function listAdmins(tenantId: string): Promise<OrgAdmin[]> {
  return (await jsonOrThrow(await authedFetch(`/api/v1/tenants/${tenantId}/admins`))).items;
}
export async function createAdmin(tenantId: string, email: string, password: string,
                                  role: "viewer" | "auditor"): Promise<OrgAdmin> {
  return jsonOrThrow(await authedFetch(`/api/v1/tenants/${tenantId}/admins`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ email, password, role }),
  }));
}
export async function setAdminRole(tenantId: string, adminId: string,
                                   role: "viewer" | "auditor"): Promise<OrgAdmin> {
  return jsonOrThrow(await authedFetch(`/api/v1/tenants/${tenantId}/admins/${adminId}/role`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ role }),
  }));
}
export async function deleteAdmin(tenantId: string, adminId: string): Promise<void> {
  await jsonOrThrow(await authedFetch(`/api/v1/tenants/${tenantId}/admins/${adminId}`, { method: "DELETE" }));
}

// org admin — own token
export interface MyTenant { id: string; name: string; token: string; created_at: string | null; }
export async function getMyTenant(): Promise<MyTenant> {
  return jsonOrThrow(await authedFetch("/api/v1/my/tenant"));
}
export async function rotateMyToken(): Promise<MyTenant> {
  return jsonOrThrow(await authedFetch("/api/v1/my/tenant/rotate-token", { method: "POST" }));
}
