// Admin session: login → JWT in localStorage, authed fetch, logout.

const KEY = "pa.token";
const PKEY = "pa.profile";

export interface Profile {
  email: string;
  role: "platform" | "org";
  cap?: "viewer" | "auditor";   // intra-tenant capability (spec 0003)
  tenant?: string | null;   // org id — null/absent for the platform superadmin
  org_name?: string | null;
}

export function getToken(): string | null { return localStorage.getItem(KEY); }
export function getProfile(): Profile | null {
  const s = localStorage.getItem(PKEY);
  return s ? (JSON.parse(s) as Profile) : null;
}

/** Can this admin reveal full prompt text / export? Platform always; org admins need the auditor role. */
export function canViewFull(): boolean {
  const p = getProfile();
  return p?.role === "platform" || p?.cap === "auditor";
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

// ---- OIDC SSO (spec 0008) ----

export const ssoLoginUrl = "/api/v1/auth/oidc/login";

/** Is native OIDC SSO configured on the server? (controls whether the SSO button shows) */
export async function ssoEnabled(): Promise<boolean> {
  try {
    const r = await fetch("/api/v1/auth/oidc/status");
    return r.ok && (await r.json()).enabled === true;
  } catch {
    return false;
  }
}

/**
 * Handle the SSO callback redirect (`/?sso=<jwt>` or `/?sso_error=…`). Stores the token, hydrates the
 * profile via /auth/me, and cleans the URL. Returns null if this isn't an SSO redirect.
 */
export async function consumeSsoRedirect(): Promise<{ ok: boolean; error?: string } | null> {
  const params = new URLSearchParams(window.location.search);
  const sso = params.get("sso");
  const err = params.get("sso_error");
  if (!sso && !err) return null;
  window.history.replaceState({}, "", window.location.pathname);   // scrub the token from the URL
  if (err) return { ok: false, error: err };
  localStorage.setItem(KEY, sso!);
  const r = await fetch("/api/v1/auth/me", { headers: { Authorization: `Bearer ${sso}` } });
  if (!r.ok) { localStorage.removeItem(KEY); return { ok: false, error: "SSO session invalid" }; }
  localStorage.setItem(PKEY, JSON.stringify(await r.json()));
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
