import { useEffect, useState } from "react";
import { getIntegrity, Integrity } from "../api";

/**
 * Tamper-evident chain status (spec 0001 / #1). Subtle green when the audit log verifies,
 * prominent red — naming the broken chain + first broken record — when it doesn't.
 */
export function IntegrityBanner() {
  const [data, setData] = useState<Integrity | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function check() {
    setBusy(true);
    try { setData(await getIntegrity()); setErr(null); }
    catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  useEffect(() => { check(); }, []);

  if (err) return null;            // don't nag if the check itself is unavailable
  if (!data) return null;

  const recheck = (
    <button className="btn ghost small" disabled={busy} onClick={check}>
      {busy ? "checking…" : "re-check"}
    </button>
  );

  if (data.ok) {
    const checked = data.chains.reduce((a, c) => a + c.checked, 0);
    return (
      <div className="integrity ok">
        <span>✅ Integrity verified · {checked} record{checked === 1 ? "" : "s"} chained &amp; intact</span>
        {recheck}
      </div>
    );
  }

  const broken = data.chains.filter((c) => !c.ok);
  const detail = broken.map((c) => `chain ${c.chain} broken at ${c.first_broken_id}`).join("; ");
  return (
    <div className="integrity broken">
      <span>
        ⚠️ <strong>Audit log integrity FAILED</strong> — {detail}. A record was deleted or modified
        after it was logged.
      </span>
      {recheck}
    </div>
  );
}
