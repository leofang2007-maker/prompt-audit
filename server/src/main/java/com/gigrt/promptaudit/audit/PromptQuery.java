package com.gigrt.promptaudit.audit;

import java.time.Instant;

/** Filter parameters shared by the list and export endpoints. Null/blank fields are ignored. */
public class PromptQuery {
    /** Server-enforced tenant isolation (from the caller's JWT). Not a user-supplied filter. */
    public String tenantOrgId;
    public Instant from;        // received_at >= from
    public Instant to;          // received_at <= to
    public String userEmail;    // exact match
    public String orgId;        // exact match
    public String userUid;      // exact match
    public String repo;         // exact match
    public String sessionId;    // exact match
    public String keyword;      // case-insensitive substring of the prompt text
}
