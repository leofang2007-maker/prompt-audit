package com.gigrt.promptaudit.tenant;

import javax.persistence.*;
import java.time.Instant;

/**
 * An organization = a tenant. Owns exactly one ingest token (the write-side credential handed to
 * that org's client hooks) and has one or more org-admins who can read ONLY this org's audit log.
 *
 * The tenant id is the trusted partition key: a prompt's owning org is derived from the ingest
 * token used to report it (stamped as prompt_record.tenant_org_id), never from client-claimed fields.
 */
@Entity
@Table(name = "tenant", indexes = {
        @Index(name = "uk_tenant_token", columnList = "token", unique = true),
        @Index(name = "uk_tenant_name", columnList = "name", unique = true)
})
public class Tenant {

    @Id
    @Column(length = 48)
    private String id;

    @Column(length = 256, nullable = false)
    private String name;

    /** The org's ingest token (bearer). Rotatable. */
    @Column(length = 80, nullable = false)
    private String token;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Tenant() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
