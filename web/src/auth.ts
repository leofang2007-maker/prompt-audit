// Admin session: login → JWT in localStorage, authed fetch, logout. (Same shape as PrismAtlas web.)

const KEY = "pa.token";
const PKEY = "pa.profile";

export interface Profile {
  email: string;
  role: "platform" | "org";
  tenant?: string | null;   // org id — null/absent for the platform superadmin
  org_name?: string | null;
}

export function getToken(): string | null { return localStorage.getItem(KEY); }
export function getProfile(): Profile | null {
  const s = localStorage.getItem(PKEY);
  return s ? (JSON.parse(s) as Profile) : null;
}

export async function login(email: string, password: string): Promise<{ ok: boolean; error?: string }> {
  const r = await fetch("/api/v1/auth/login", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!r.ok) return { ok: false, error: r.status === 401 ? "Invalid email or password" : "Login failed" };
  const d = await r.json();
  localStorage.setItem(KEY, d.token);
  localStorage.setItem(PKEY, JSON.stringify(d.profile));
  return { ok: true };
}

export async function logout() {
  try { await authedFetch("/api/v1/auth/logout", { method: "POST" }); } catch { /* stateless */ }
  localStorage.removeItem(KEY);
  localStorage.removeItem(PKEY);
}

/** fetch with the admin Bearer token; on 401 drops the session and signals the app to show login. */
export async function authedFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const token = getToken();
  const headers = { ...(init.headers || {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) };
  const r = await fetch(url, { ...init, headers });
  if (r.status === 401) {
    localStorage.removeItem(KEY);
    localStorage.removeItem(PKEY);
    window.dispatchEvent(new Event("pa-unauth"));
  }
  return r;
}
