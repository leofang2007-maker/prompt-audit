package com.gigrt.promptaudit.auth;

import com.gigrt.promptaudit.tenant.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin login → session JWT. Authenticates the platform superadmin (from env) or an org-admin
 * (from the DB) via {@link TenantService}. The JWT carries the role + tenant so every read is scoped.
 *
 * Entirely independent from the ingest tokens (see {@link SecurityInterceptor}).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final TenantService tenants;
    private final JwtUtil jwt;

    public AuthController(TenantService tenants, JwtUtil jwt) { this.tenants = tenants; this.jwt = jwt; }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body) {
        String email = body != null ? str(body.get("email")) : null;
        String password = body != null ? str(body.get("password")) : null;

        TenantService.Identity id = tenants.authenticate(email, password);
        if (id == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Collections.singletonMap("error", "invalid_credentials"));
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", id.email);
        claims.put("role", id.role);                       // "platform" | "org"
        claims.put("cap", id.cap);                         // "viewer" | "auditor" (spec 0003)
        if (id.adminId != null) claims.put("aid", id.adminId);        // access-log actor id
        if (id.tenantId != null) claims.put("tenant", id.tenantId);   // org id — absent for platform
        if (id.orgName != null) claims.put("org_name", id.orgName);
        String token = jwt.issue(claims);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("email", id.email);
        profile.put("role", id.role);
        profile.put("cap", id.cap);
        profile.put("tenant", id.tenantId);
        profile.put("org_name", id.orgName);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("profile", profile);
        return ResponseEntity.ok(resp);
    }

    /** Stateless logout — client drops the token. */
    @PostMapping("/logout")
    public Map<String, Object> logout() { return Collections.singletonMap("ok", true); }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
