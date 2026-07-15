import { useEffect, useState } from "react";
import { getProfile, getToken, logout } from "./auth";
import { Login } from "./pages/Login";
import { AuditList } from "./pages/AuditList";

export function App() {
  const [authed, setAuthed] = useState<boolean>(!!getToken());

  // A 401 anywhere (expired/invalid token) drops us back to the login screen.
  useEffect(() => {
    const onUnauth = () => setAuthed(false);
    window.addEventListener("pa-unauth", onUnauth);
    return () => window.removeEventListener("pa-unauth", onUnauth);
  }, []);

  if (!authed) return <Login onLoggedIn={() => setAuthed(true)} />;

  const profile = getProfile();
  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">◧</span> Prompt Audit
        </div>
        <div className="topbar-right">
          <span className="who">{profile?.email}</span>
          <button className="btn ghost" onClick={async () => { await logout(); setAuthed(false); }}>
            Sign out
          </button>
        </div>
      </header>
      <main className="main">
        <AuditList />
      </main>
    </div>
  );
}
