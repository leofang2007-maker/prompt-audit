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

    @Column(length = 512)
    private String repo;

    @Column(length = 256)
    private String branch;

    @Column(length = 1024)
    private String cwd;

    @Column(length = 256)
    private String hostname;

    /** Full original prompt text (may be long, multi-line, non-ASCII). */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String prompt;

    /** Character length of the prompt — cheap to index/sort and safe to log (the prompt itself is not). */
    @Column(name = "prompt_length")
    private int promptLength;

    public PromptRecord() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }
    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public int getPromptLength() { return promptLength; }
    public void setPromptLength(int promptLength) { this.promptLength = promptLength; }
}
