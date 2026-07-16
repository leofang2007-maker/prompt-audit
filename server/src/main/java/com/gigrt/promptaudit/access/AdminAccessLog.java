package com.gigrt.promptaudit.access;

import javax.persistence.*;
import java.time.Instant;

/**
 * One privileged read of prompt content by an admin (spec 0003) — the "who watched the watchers"
 * record. Written whenever full prompt text is revealed (detail view) or exported; ordinary listing
 * of redacted previews is NOT logged.
 *
 * Scoped by {@link #tenantOrgId} = the tenant whose DATA was accessed (not the actor's tenant), so the
 * org whose prompts were viewed can see it — including views by the platform superadmin. The log is
 * itself hash-chained (same primitive as spec 0001) so an admin can't scrub the evidence that they looked.
 */
@Entity
@Table(name = "admin_access_log", indexes = {
        @Index(name = "idx_acl_tenant", columnList = "tenant_org_id"),
        @Index(name = "idx_acl_created", columnList = "created_at")
})
public class AdminAccessLog {

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "actor_email", length = 320)
    private String actorEmail;

    /** Effective full-access role at access time: "platform" or "auditor" (viewers never reach here). */
    @Column(name = "actor_role", length = 16)
    private String actorRole;

    /** Tenant whose data was accessed — null = the tenantless/global partition (platform-wide export). */
    @Column(name = "tenant_org_id", length = 48)
    private String tenantOrgId;

    /** "view_detail" | "export". */
    @Column(name = "action", length = 16)
    private String action;

    /** For view_detail: the prompt record id opened. Null for export. */
    @Column(name = "target_record_id", length = 40)
    private String targetRecordId;

    /** For export: the filter set (JSON). Null for view_detail. */
    @Lob
    @Column(name = "query_json", columnDefinition = "TEXT")
    private String queryJson;

    /** The reason the actor gave for this access (break-glass). */
    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // ---- tamper-evident chain (mirrors spec 0001) ----
    @Column(name = "record_hash", length = 64)
    private String recordHash;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(name = "chain_seq")
    private Long chainSeq;

    public AdminAccessLog() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }
    public String getTenantOrgId() { return tenantOrgId; }
    public void setTenantOrgId(String tenantOrgId) { this.tenantOrgId = tenantOrgId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetRecordId() { return targetRecordId; }
    public void setTargetRecordId(String targetRecordId) { this.targetRecordId = targetRecordId; }
    public String getQueryJson() { return queryJson; }
    public void setQueryJson(String queryJson) { this.queryJson = queryJson; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getRecordHash() { return recordHash; }
    public void setRecordHash(String recordHash) { this.recordHash = recordHash; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public Long getChainSeq() { return chainSeq; }
    public void setChainSeq(Long chainSeq) { this.chainSeq = chainSeq; }
}
