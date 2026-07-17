import { useEffect, useState } from "react";
import { login, ssoEnabled, ssoLoginUrl } from "../auth";

export function Login({ onLoggedIn, ssoError }: { onLoggedIn: () => void; ssoError?: string | null }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(ssoError ?? null);
  const [busy, setBusy] = useState(false);
  const [sso, setSso] = useState(false);

  useEffect(() => { ssoEnabled().then(setSso); }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const r = await login(email.trim(), password);
    setBusy(false);
    if (r.ok) onLoggedIn();
    else setError(r.error ?? "Login failed");
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand"><span className="brand-mark">◧</span> Prompt Audit</div>
        <p className="login-sub">Compliance console — admin sign in</p>
        <label className="field">
          <span>Email</span>
          <input type="email" autoFocus autoComplete="off" value={email}
                 onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          <span>Password</span>
          <input type="password" autoComplete="off" value={password}
                 onChange={(e) => setPassword(e.target.value)} />
        </label>
        {error && <div className="error">{error}</div>}
        <button className="btn primary" type="submit" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
        {sso && (
          <>
            <div className="login-or"><span>or</span></div>
            <a className="btn" href={ssoLoginUrl}>Sign in with SSO</a>
          </>
        )}
      </form>
    </div>
  );
}
