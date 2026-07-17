import { useEffect, useState } from "react";
import { canViewFull, consumeSsoRedirect, getProfile, getToken, logout } from "./auth";
import { Login } from "./pages/Login";
import { AuditList } from "./pages/AuditList";
import { TenantsPage } from "./pages/TenantsPage";
import { MyTokenPage } from "./pages/MyTokenPage";
import { AccessLogPage } from "./pages/AccessLogPage";
import { TransparencyPage } from "./pages/TransparencyPage";
import { CoveragePage } from "./pages/CoveragePage";
import { EvidencePage } from "./pages/EvidencePage";
import { SsoSettingsPage } from "./pages/SsoSettingsPage";

type View = "audit" | "access" | "coverage" | "evidence" | "tenants" | "mytoken" | "transparency" | "sso";

export function App() {
  const [authed, setAuthed] = useState<boolean>(!!getToken());
  const [view, setView] = useState<View>("audit");
  const [ssoError, setSsoError] = useState<string | null>(null);
  const [booting, setBooting] = useState<boolean>(
    new URLSearchParams(window.location.search).has("sso") ||
    new URLSearchParams(window.location.search).has("sso_error"));

  // A 401 anywhere (expired/invalid token) drops us back to the login screen.
  useEffect(() => {
    const onUnauth = () => setAuthed(false);
    window.addEventListener("pa-unauth", onUnauth);
    return () => window.removeEventListener("pa-unauth", onUnauth);
  }, []);

  // Handle an OIDC SSO redirect (?sso=… / ?sso_error=…) on first load.
  useEffect(() => {
    consumeSsoRedirect().then((r) => {
      if (r?.ok) { setView("audit"); setAuthed(true); }
      else if (r?.error) setSsoError(r.error);
      setBooting(false);
    });
  }, []);

  if (booting) return <div className="login-wrap"><span className="muted">Signing in…</span></div>;
  if (!authed) return <Login onLoggedIn={() => { setView("audit"); setAuthed(true); }} ssoError={ssoError} />;

  const profile = getProfile();
  const isPlatform = profile?.role === "platform";

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand"><span className="brand-mark">◧</span> Prompt Audit</div>
        <nav className="tabs">
          <button className={"tab" + (view === "audit" ? " active" : "")} onClick={() => setView("audit")}>
            Audit log
          </button>
          <button className={"tab" + (view === "access" ? " active" : "")} onClick={() => setView("access")}>
            Access log
          </button>
          <button className={"tab" + (view === "coverage" ? " active" : "")} onClick={() => setView("coverage")}>
            Coverage
          </button>
          {canViewFull() && (
            <button className={"tab" + (view === "evidence" ? " active" : "")} onClick={() => setView("evidence")}>
              Evidence
            </button>
          )}
          {isPlatform && (
            <button className={"tab" + (view === "tenants" ? " active" : "")} onClick={() => setView("tenants")}>
              Organizations
            </button>
          )}
          {isPlatform && (
            <button className={"tab" + (view === "sso" ? " active" : "")} onClick={() => setView("sso")}>
              SSO
            </button>
          )}
          {!isPlatform && (
            <button className={"tab" + (view === "mytoken" ? " active" : "")} onClick={() => setView("mytoken")}>
              My ingest token
            </button>
          )}
          <button className={"tab" + (view === "transparency" ? " active" : "")} onClick={() => setView("transparency")}>
            Transparency
          </button>
        </nav>
        <div className="topbar-right">
          <span className="who">
            {profile?.email}
            {isPlatform ? " · platform admin" : profile?.org_name ? ` · ${profile.org_name}` : ""}
          </span>
          <button className="btn ghost" onClick={async () => { await logout(); setAuthed(false); }}>
            Sign out
          </button>
        </div>
      </header>
      <main className="main">
        {view === "audit" && <AuditList />}
        {view === "access" && <AccessLogPage />}
        {view === "coverage" && <CoveragePage />}
        {view === "evidence" && canViewFull() && <EvidencePage />}
        {view === "tenants" && isPlatform && <TenantsPage />}
        {view === "sso" && isPlatform && <SsoSettingsPage />}
        {view === "mytoken" && !isPlatform && <MyTokenPage />}
        {view === "transparency" && <TransparencyPage />}
      </main>
    </div>
  );
}
