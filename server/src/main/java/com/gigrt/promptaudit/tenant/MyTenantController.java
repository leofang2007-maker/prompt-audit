package com.gigrt.promptaudit.tenant;

import com.gigrt.promptaudit.auth.SecurityInterceptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Org-admin self-service: view and rotate YOUR OWN org's ingest token. Scoped to the caller's
 * tenant (from the JWT) — an org admin can never touch another org. The platform admin has no
 * tenant of its own, so it uses /api/v1/tenants instead.
 */
@RestController
@RequestMapping("/api/v1/my")
public class MyTenantController {

    private final TenantService service;

    public MyTenantController(TenantService service) { this.service = service; }

    @GetMapping("/tenant")
    public ResponseEntity<Map<String, Object>> myTenant(HttpServletRequest req) {
        Tenant t = callerTenant(req);
        return t == null ? noTenant() : ResponseEntity.ok(dto(t));
    }

    @PostMapping("/tenant/rotate-token")
    public ResponseEntity<Map<String, Object>> rotate(HttpServletRequest req) {
        Tenant t = callerTenant(req);
        if (t == null) return noTenant();
        return ResponseEntity.ok(dto(service.rotateToken(t.getId())));
    }

    private Tenant callerTenant(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        Object tenantId = p == null ? null : p.get("tenant");
        return tenantId == null ? null : service.getTenant(String.valueOf(tenantId));
    }

    private static ResponseEntity<Map<String, Object>> noTenant() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Collections.singletonMap("error", "no_tenant_for_this_account"));
    }

    private static Map<String, Object> dto(Tenant t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("token", t.getToken());
        m.put("created_at", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        return m;
    }
}
