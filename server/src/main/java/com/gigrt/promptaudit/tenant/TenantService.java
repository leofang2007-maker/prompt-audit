package com.gigrt.promptaudit.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tenancy core: resolves ingest tokens → owning tenant (trusted partition key), authenticates admins
 * (platform superadmin from env, or org-admins from DB), and manages tenants + their admins.
 */
@Service
public class TenantService {

    public static final String ROLE_PLATFORM = "platform";
    public static final String ROLE_ORG = "org";

    private final TenantRepository tenants;
    private final AdminUserRepository admins;
    private final String globalToken;      // env INGEST_TOKEN — bootstrap; its data is tenantless
    private final String platformEmail;
    private final String platformPassword;
    private final SecureRandom rng = new SecureRandom();

    public TenantService(TenantRepository tenants, AdminUserRepository admins,
                         @Value("${app.ingest.token:}") String globalToken,
                         @Value("${app.admin.email:admin@promptaudit.local}") String platformEmail,
                         @Value("${app.admin.password:changeme}") String platformPassword) {
        this.tenants = tenants;
        this.admins = admins;
        this.globalToken = globalToken;
        this.platformEmail = platformEmail;
        this.platformPassword = platformPassword;
    }

    // ---- ingest (write side) ----

    /** Result of resolving an ingest token: validity + the trusted owning tenant (null = global/tenantless). */
    public static final class IngestAuth {
        public static final IngestAuth INVALID = new IngestAuth(false, null, null);
        public final boolean valid;
        public final String tenantOrgId;   // stamped onto the record; null for the global token
        public final String tenantName;
        IngestAuth(boolean valid, String tenantOrgId, String tenantName) {
            this.valid = valid; this.tenantOrgId = tenantOrgId; this.tenantName = tenantName;
        }
    }

    public IngestAuth resolveIngest(String presented) {
        if (presented == null || presented.isEmpty()) return IngestAuth.INVALID;
        if (globalToken != null && !globalToken.isEmpty() && constantEquals(presented, globalToken)) {
            return new IngestAuth(true, null, null);   // bootstrap/global — tenantless
        }
        Tenant t = tenants.findByToken(presented);
        return t != null ? new IngestAuth(true, t.getId(), t.getName()) : IngestAuth.INVALID;
    }

    // ---- admin login (read side) ----

    /** Who a successful login is. */
    public static final class Identity {
        public final String email, role, tenantId, orgName;
        Identity(String email, String role, String tenantId, String orgName) {
            this.email = email; this.role = role; this.tenantId = tenantId; this.orgName = orgName;
        }
    }

    /** Platform superadmin (env) first, then org-admins (DB). Null ⇒ bad credentials. */
    public Identity authenticate(String email, String password) {
        if (email == null || password == null) return null;
        if (constantEquals(email, platformEmail) && constantEquals(password, platformPassword)) {
            return new Identity(platformEmail, ROLE_PLATFORM, null, null);
        }
        AdminUser u = admins.findByEmailIgnoreCase(email);
        if (u != null && PasswordHash.verify(password, u.getPasswordHash())) {
            Tenant t = tenants.findById(u.getTenantId()).orElse(null);
            return new Identity(u.getEmail(), ROLE_ORG, u.getTenantId(), t != null ? t.getName() : null);
        }
        return null;
    }

    // ---- tenant management (platform) ----

    public List<Tenant> listTenants() { return tenants.findAll(Sort.by(Sort.Direction.DESC, "createdAt")); }
    public Tenant getTenant(String id) { return tenants.findById(id).orElse(null); }
    public long adminCount(String tenantId) { return admins.countByTenantId(tenantId); }

    public Tenant createTenant(String name) {
        if (tenants.findByNameIgnoreCase(name) != null) throw new ConflictException("org_name_taken");
        Tenant t = new Tenant();
        t.setId("org_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        t.setName(name);
        t.setToken(newTokenValue());
        t.setCreatedAt(Instant.now());
        return tenants.save(t);
    }

    public Tenant rotateToken(String tenantId) {
        Tenant t = tenants.findById(tenantId).orElse(null);
        if (t == null) return null;
        t.setToken(newTokenValue());
        return tenants.save(t);
    }

    /** Delete a tenant and its admins. (Its audit rows are left as-is — tenantless historical data.) */
    public boolean deleteTenant(String tenantId) {
        if (!tenants.existsById(tenantId)) return false;
        for (AdminUser u : admins.findByTenantId(tenantId)) admins.deleteById(u.getId());
        tenants.deleteById(tenantId);
        return true;
    }

    // ---- org-admin management (platform) ----

    public List<AdminUser> listAdmins(String tenantId) { return admins.findByTenantId(tenantId); }

    public AdminUser createAdmin(String tenantId, String email, String password) {
        if (!tenants.existsById(tenantId)) throw new NotFoundException("tenant");
        if (email == null || email.trim().isEmpty() || password == null || password.isEmpty())
            throw new ConflictException("email_and_password_required");
        if (constantEquals(email.trim(), platformEmail) || admins.findByEmailIgnoreCase(email.trim()) != null)
            throw new ConflictException("email_taken");
        AdminUser u = new AdminUser();
        u.setId("adm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        u.setEmail(email.trim());
        u.setPasswordHash(PasswordHash.hash(password));
        u.setTenantId(tenantId);
        u.setCreatedAt(Instant.now());
        return admins.save(u);
    }

    public boolean deleteAdmin(String tenantId, String adminId) {
        AdminUser u = admins.findById(adminId).orElse(null);
        if (u == null || !u.getTenantId().equals(tenantId)) return false;
        admins.deleteById(adminId);
        return true;
    }

    // ---- helpers ----

    private String newTokenValue() {
        byte[] b = new byte[24];
        rng.nextBytes(b);
        StringBuilder sb = new StringBuilder("iat_");
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
        return r == 0;
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String m) { super(m); }
    }
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String m) { super(m); }
    }
}
