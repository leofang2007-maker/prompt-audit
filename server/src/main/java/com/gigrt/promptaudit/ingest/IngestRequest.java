package com.gigrt.promptaudit.ingest;

/**
 * Fixed ingest contract (client-hook payload). Do not rename fields — clients depend on them.
 * Only `prompt` is required; the rest are best-effort context.
 */
public class IngestRequest {
    public String event_id;    // idempotency key (IDE request_set_id UUID, or legacy sha256); optional
    public String timestamp;   // RFC3339 UTC, client event time
    public String session_id;
    public String user_email;
    public String user_name;   // v1.0.2 — identity (all nullable, fail-open)
    public String user_uid;
    public String org_id;
    public String org_name;
    public String repo;
    public String branch;
    public String cwd;
    public String transcript_path;  // v1.0.2 — abs path to conversation JSONL on the reporting machine
    public String hostname;
    public String prompt;      // required
}
