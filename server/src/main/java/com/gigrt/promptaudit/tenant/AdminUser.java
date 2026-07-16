package com.gigrt.promptaudit.tenant;

import javax.persistence.*;
import java.time.Instant;

/**
 * An org-admin login. Scoped to exactly one tenant — sees only that org's audit log.
 * (The platform superadmin is NOT stored here; it comes from env and has no tenant.)
 */
@Entity
@Table(name = "admin_user", indexes = {
        @Index(name = "uk_admin_email", columnList = "email", unique = true),
        @Index(name = "idx_admin_tenant", columnList = "tenant_id")
})
public class AdminUser {

    @Id
    @Column(length = 48)
    private String id;

    @Column(length = 320, nullable = false)
    private String email;

    /** PBKDF2 hash string ("pbkdf2$iterations$saltB64$hashB64"). Never the plaintext. */
    @Column(name = "password_hash", length = 256, nullable = false)
    private String passwordHash;

    @Column(name = "tenant_id", length = 48, nullable = false)
    private String tenantId;

    /** Intra-tenant capability role (spec 0003): "viewer" (metadata + redacted previews) or
     *  "auditor" (may reveal full prompt text / export, always access-logged). NULL on legacy rows —
     *  read as "auditor" to preserve pre-0003 behavior; new admins default to "viewer". */
    @Column(name = "role", length = 16)
    private String role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AdminUser() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
