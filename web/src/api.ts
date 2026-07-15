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
  prompt_preview: string;
}

export interface Detail extends Summary {
  event_id: string | null;
  user_uid: string | null;
  org_id: string | null;
  cwd: string | null;
  transcript_path: string | null;
  prompt: string | null;
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

export async function getPrompt(id: string): Promise<Detail> {
  const r = await authedFetch(`/api/v1/prompts/${encodeURIComponent(id)}`);
  if (!r.ok) throw new Error(`detail failed: ${r.status}`);
  return r.json();
}

/** Export URL carries the token in the query so a plain browser navigation (download) is authorized. */
export function exportUrl(f: Filters, format: "csv" | "json"): string {
  const token = getToken() ?? "";
  return `/api/v1/prompts/export?${query(f, { format, token })}`;
}
