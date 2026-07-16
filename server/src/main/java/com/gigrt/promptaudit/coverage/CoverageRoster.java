package com.gigrt.promptaudit.coverage;

import javax.persistence.*;
import java.time.Instant;

/**
 * One expected-to-report entity (a hostname) on a tenant's coverage roster (spec 0004). Uploading a
 * roster enables the "never-reported" signal — machines that SHOULD be reporting but never have.
 * {@code scopeKey} is the tenant id, or "__global__" for the platform-wide roster.
 */
@Entity
@Table(name = "coverage_roster", indexes = {
        @Index(name = "idx_cvr_scope", columnList = "scope_key")
})
public class CoverageRoster {

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "scope_key", length = 48, nullable = false)
    private String scopeKey;

    @Column(name = "entity", length = 256, nullable = false)
    private String entity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public CoverageRoster() {}

    public CoverageRoster(String id, String scopeKey, String entity, Instant createdAt) {
        this.id = id; this.scopeKey = scopeKey; this.entity = entity; this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }
    public String getEntity() { return entity; }
    public void setEntity(String entity) { this.entity = entity; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
