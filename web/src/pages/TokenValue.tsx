import { useState } from "react";

/** Reveal/hide + copy control for a secret token value. */
export function TokenValue({ token }: { token: string }) {
  const [shown, setShown] = useState(false);
  const [copied, setCopied] = useState(false);

  async function copy() {
    await navigator.clipboard.writeText(token);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }

  const masked = token.slice(0, 4) + "•".repeat(Math.max(0, token.length - 8)) + token.slice(-4);
  return (
    <span className="token-value">
      <code className="mono">{shown ? token : masked}</code>
      <button className="btn ghost small" onClick={() => setShown((s) => !s)}>{shown ? "Hide" : "Show"}</button>
      <button className="btn ghost small" onClick={copy}>{copied ? "Copied ✓" : "Copy"}</button>
    </span>
  );
}
