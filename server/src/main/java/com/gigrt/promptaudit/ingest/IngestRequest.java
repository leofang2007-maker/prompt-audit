package com.gigrt.promptaudit.ingest;

/**
 * Fixed ingest contract (client-hook payload). Do not rename fields — clients depend on them.
 * Only `prompt` is required; the rest are best-effort context.
 */
public class IngestRequest {
    public String timestamp;   // RFC3339 UTC, client event time
    public String session_id;
    public String user_email;
    public String repo;
    public String branch;
    public String cwd;
    public String hostname;
    public String prompt;      // required
}
