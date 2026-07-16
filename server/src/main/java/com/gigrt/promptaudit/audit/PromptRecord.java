package com.gigrt.promptaudit.audit;

import javax.persistence.*;
import java.time.Instant;

/**
 * One audited prompt as reported by a client hook.
 *
 * Indexes match the four filter dimensions the audit UI queries by
 * (received_at range, user_email, repo, session_id) so list/export stay fast as the log grows.
 */
@Entity
@Table(name = "prompt_record", indexes = {
        @Index(name = "idx_received_at", columnList = "received_at"),
        @Index(name = "idx_user_email", columnList = "user_email"),
        @Index(name = "idx_repo", columnList = "repo"),
        @Index(name = "idx_session_id", columnList = "session_id"),
        // Trusted tenant partition (from the ingest token) — every admin read is filtered by it.
        @Index(name = "idx_tenant_org_id", columnList = "tenant_org_id"),
        // Identity dimensions (v1.0.2) — exact-match compliance filters.
        @Index(name = "idx_org_id", columnList = "org_id"),
        @Index(name = "idx_user_uid", columnList = "user_uid"),
        // Idempotency key: the client sends a deterministic event_id per logical submission; the
        // gateway dedups on it so an IDE's double-fire / drain retries never write duplicate rows.
        // UNIQUE — MySQL allows multiple NULLs, so pre-event_id rows (NULL) don't collide.
        @Index(name = "uk_event_id", columnList = "event_id", unique = true)
})
public class PromptRecord {

    /** Server-generated id, e.g. "pr_9f3a1c8b7d6e4f21". */
    @Id
    @Column(length = 40)
    private String id;

    /** Client-supplied deterministic idempotency key (sha256 hex, 64 chars). Nullable for old clients. */
    @Column(name = "event_id", length = 64)
    private String eventId;

    /** TRUSTED owning tenant, derived from the ingest token (NOT the client-claimed org_id).
     *  Null = reported via the global bootstrap token (visible only to the platform superadmin). */
    @Column(name = "tenant_org_id", length = 48)
    private String tenantOrgId;

    /** Event time reported by the client (RFC3339 UTC), parsed to an instant. */
    @Column(name = "event_ts")
    private Instant timestamp;

    /** When the server accepted the report. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "user_email", length = 320)
    private String userEmail;

    // ---- identity (v1.0.2) — all nullable (unauthenticated / older IDE ⇒ empty) ----
    @Column(name = "user_name", length = 256)
    private String userName;

    @Column(name = "user_uid", length = 64)
    private String userUid;

    @Column(name = "org_id", length = 64)
    private String orgId;

    @Column(name = "org_name", length = 256)
    private String orgName;

    @Column(length = 512)
    private String repo;

    @Column(length = 256)
    private String branch;

    @Column(length = 1024)
    private String cwd;

    /** Absolute path (on the reporting machine) to the full conversation JSONL. Stored as an opaque
     *  string — never fetched/parsed here; combine with {@link #hostname} for provenance. */
    @Column(name = "transcript_path", length = 1024)
    private String transcriptPath;

    @Column(length = 256)
    private String hostname;

    /** Full original prompt text (may be long, multi-line, non-ASCII). */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String prompt;

    /** Character length of the prompt — cheap to index/sort and safe to log (the prompt itself is not).
     *  This is the length of the STORED (redacted) prompt, per spec 0002. */
    @Column(name = "prompt_length")
    private int promptLength;

    // ---- secret redaction at capture (spec 0002) ----
    /** How many secrets were masked out of this prompt before it was stored (0 = none / disabled). */
    @Column(name = "redaction_count")
    private int redactionCount;

    /** Distinct secret types masked, sorted & comma-joined (e.g. "aws_key,jwt"); empty when none. */
    @Column(name = "redacted_types", length = 256)
    private String redactedTypes;

    // ---- tamper-evident chain (spec 0001); nullable until backfilled ----
    @Column(name = "record_hash", length = 64)
    private String recordHash;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(name = "chain_seq")
    private Long chainSeq;

    public PromptRecord() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getTenantOrgId() { return tenantOrgId; }
    public void setTenantOrgId(String tenantOrgId) { this.tenantOrgId = tenantOrgId; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getUserUid() { return userUid; }
    public void setUserUid(String userUid) { this.userUid = userUid; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }
    public String getTranscriptPath() { return transcriptPath; }
    public void setTranscriptPath(String transcriptPath) { this.transcriptPath = transcriptPath; }
    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public int getPromptLength() { return promptLength; }
    public void setPromptLength(int promptLength) { this.promptLength = promptLength; }
    public int getRedactionCount() { return redactionCount; }
    public void setRedactionCount(int redactionCount) { this.redactionCount = redactionCount; }
    public String getRedactedTypes() { return redactedTypes; }
    public void setRedactedTypes(String redactedTypes) { this.redactedTypes = redactedTypes; }
    public String getRecordHash() { return recordHash; }
    public void setRecordHash(String recordHash) { this.recordHash = recordHash; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public Long getChainSeq() { return chainSeq; }
    public void setChainSeq(Long chainSeq) { this.chainSeq = chainSeq; }
}
