import { useEffect, useState } from "react";
import { Detail as DetailRecord, getPrompt } from "../api";

/** Modal showing a full audit record + the complete prompt text. `reason` (when the caller is an
 *  auditor/platform admin) is sent so the full-text reveal is access-logged (spec 0003). */
export function Detail({ id, reason, onClose }: { id: string; reason?: string; onClose: () => void }) {
  const [rec, setRec] = useState<DetailRecord | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    getPrompt(id, reason).then(setRec).catch((e) => setErr((e as Error).message));
  }, [id, reason]);

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
              <Meta label="Event ID" value={rec.event_id} mono />
              <Meta label="Event time" value={rec.timestamp} mono />
              <Meta label="Received" value={rec.received_at} mono />
              <Meta label="User email" value={rec.user_email} />
              <Meta label="User name" value={rec.user_name} />
              <Meta label="User UID" value={rec.user_uid} mono />
              <Meta label="Org" value={rec.org_name} />
              <Meta label="Org ID" value={rec.org_id} mono />
              <Meta label="Repo" value={rec.repo} />
              <Meta label="Branch" value={rec.branch} />
              <Meta label="Host" value={rec.hostname} />
              <Meta label="Session" value={rec.session_id} mono />
              <Meta label="CWD" value={rec.cwd} mono />
              <Meta label="Transcript" value={rec.transcript_path} mono />
              <Meta label="Prompt length" value={String(rec.prompt_length)} />
            </div>
            {rec.redaction_count > 0 && (
              <div className="redaction-notice">
                🛡 <strong>{rec.redaction_count} secret{rec.redaction_count === 1 ? "" : "s"} masked at capture</strong>
                {rec.redacted_types ? <> · {rec.redacted_types}</> : null}. The value{rec.redaction_count === 1 ? " was" : "s were"} replaced
                with <code>[REDACTED:type]</code> before storage — the audit log never held {rec.redaction_count === 1 ? "it" : "them"}.
              </div>
            )}
            <div className="prompt-head">
              <span>Prompt</span>
              {!rec.prompt_hidden && (
                <button className="btn ghost small" onClick={copyPrompt}>{copied ? "Copied ✓" : "Copy"}</button>
              )}
            </div>
            {rec.prompt_hidden ? (
              <div className="prompt-hidden">
                🔒 Full prompt text is withheld for your role (<strong>viewer</strong>). Only an
                <strong> auditor</strong> can reveal it — and that reveal is recorded in the access log.
                {rec.prompt_preview ? <><br /><span className="muted">Redacted preview:</span> {rec.prompt_preview}</> : null}
              </div>
            ) : (
              <pre className="prompt-body">{rec.prompt}</pre>
            )}
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
