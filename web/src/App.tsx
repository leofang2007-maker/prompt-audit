import { useEffect, useState } from "react";
import { getProfile, getToken, logout } from "./auth";
import { Login } from "./pages/Login";
import { AuditList } from "./pages/AuditList";
import { TenantsPage } from "./pages/TenantsPage";
import { MyTokenPage } from "./pages/MyTokenPage";

type View = "audit" | "tenants" | "mytoken";

export function App() {
  const [authed, setAuthed] = useState<boolean>(!!getToken());
  const [view, setView] = useState<View>("audit");

  // A 401 anywhere (expired/invalid token) drops us back to the login screen.
  useEffect(() => {
    const onUnauth = () => setAuthed(false);
    window.addEventListener("pa-unauth", onUnauth);
    return () => window.removeEventListener("pa-unauth", onUnauth);
  }, []);

  if (!authed) return <Login onLoggedIn={() => { setView("audit"); setAuthed(true); }} />;

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
          {isPlatform && (
            <button className={"tab" + (view === "tenants" ? " active" : "")} onClick={() => setView("tenants")}>
              Organizations
            </button>
          )}
          {!isPlatform && (
            <button className={"tab" + (view === "mytoken" ? " active" : "")} onClick={() => setView("mytoken")}>
              My ingest token
            </button>
          )}
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
        {view === "tenants" && isPlatform && <TenantsPage />}
        {view === "mytoken" && !isPlatform && <MyTokenPage />}
      </main>
    </div>
  );
}
