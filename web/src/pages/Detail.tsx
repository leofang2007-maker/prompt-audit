import { useEffect, useState } from "react";
import { Detail as DetailRecord, getPrompt } from "../api";

/** Modal showing a full audit record + the complete prompt text. */
export function Detail({ id, onClose }: { id: string; onClose: () => void }) {
  const [rec, setRec] = useState<DetailRecord | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    getPrompt(id).then(setRec).catch((e) => setErr((e as Error).message));
  }, [id]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  async function copyPrompt() {
    if (!rec?.prompt) return;
    await navigator.clipboard.writeText(rec.prompt);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h3>Audit record</h3>
          <button className="btn ghost" onClick={onClose}>✕</button>
        </div>
        {err && <div className="error">{err}</div>}
        {!rec && !err && <div className="muted">Loading…</div>}
        {rec && (
          <>
            <div className="meta-grid">
              <Meta label="ID" value={rec.id} mono />
              <Meta label="Event time" value={rec.timestamp} mono />
              <Meta label="Received" value={rec.received_at} mono />
              <Meta label="User" value={rec.user_email} />
              <Meta label="Repo" value={rec.repo} />
              <Meta label="Branch" value={rec.branch} />
              <Meta label="Host" value={rec.hostname} />
              <Meta label="Session" value={rec.session_id} mono />
              <Meta label="CWD" value={rec.cwd} mono />
              <Meta label="Prompt length" value={String(rec.prompt_length)} />
            </div>
            <div className="prompt-head">
              <span>Prompt</span>
              <button className="btn ghost small" onClick={copyPrompt}>{copied ? "Copied ✓" : "Copy"}</button>
            </div>
            <pre className="prompt-body">{rec.prompt}</pre>
          </>
        )}
      </div>
    </div>
  );
}

function Meta({ label, value, mono }: { label: string; value: string | null; mono?: boolean }) {
  return (
    <div className="meta">
      <div className="meta-label">{label}</div>
      <div className={"meta-value" + (mono ? " mono" : "")}>{value || "—"}</div>
    </div>
  );
}
