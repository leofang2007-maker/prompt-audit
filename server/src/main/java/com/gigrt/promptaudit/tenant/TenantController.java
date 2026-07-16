package com.gigrt.promptaudit.tenant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-superadmin tenant management. Access is enforced in SecurityInterceptor:
 * these routes require an admin JWT AND role=platform.
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService service;

    public TenantController(TenantService service) { this.service = service; }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Tenant t : service.listTenants()) items.add(tenantDto(t));
        return Collections.singletonMap("items", items);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        String name = body != null ? str(body.get("name")) : null;
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Collections.singletonMap("error", "name_required"));
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(tenantDto(service.createTenant(name.trim())));
        } catch (TenantService.ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/rotate-token")
    public ResponseEntity<Map<String, Object>> rotate(@PathVariable String id) {
        Tenant t = service.rotateToken(id);
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(tenantDto(t));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        return service.deleteTenant(id)
                ? ResponseEntity.ok(Collections.singletonMap("ok", true))
                : ResponseEntity.notFound().build();
    }

    // ---- org admins under a tenant ----

    @GetMapping("/{id}/admins")
    public ResponseEntity<Map<String, Object>> listAdmins(@PathVariable String id) {
        if (service.getTenant(id) == null) return ResponseEntity.notFound().build();
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminUser u : service.listAdmins(id)) items.add(adminDto(u));
        return ResponseEntity.ok(Collections.singletonMap("items", items));
    }

    @PostMapping("/{id}/admins")
    public ResponseEntity<Map<String, Object>> createAdmin(@PathVariable String id,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        String email = body != null ? str(body.get("email")) : null;
        String password = body != null ? str(body.get("password")) : null;
        String role = body != null ? str(body.get("role")) : null;   // spec 0003; default viewer
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(adminDto(service.createAdmin(id, email, password, role)));
        } catch (TenantService.NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (TenantService.ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    /** Change an admin's capability role (viewer/auditor) — spec 0003. Platform-only (route guard). */
    @PostMapping("/{id}/admins/{adminId}/role")
    public ResponseEntity<Map<String, Object>> setAdminRole(@PathVariable String id, @PathVariable String adminId,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        String role = body != null ? str(body.get("role")) : null;
        AdminUser u = service.setAdminRole(id, adminId, role);
        return u == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(adminDto(u));
    }

    @DeleteMapping("/{id}/admins/{adminId}")
    public ResponseEntity<Map<String, Object>> deleteAdmin(@PathVariable String id, @PathVariable String adminId) {
        return service.deleteAdmin(id, adminId)
                ? ResponseEntity.ok(Collections.singletonMap("ok", true))
                : ResponseEntity.notFound().build();
    }

    // ---- dto ----

    private Map<String, Object> tenantDto(Tenant t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("token", t.getToken());
        m.put("admin_count", service.adminCount(t.getId()));
        m.put("created_at", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        return m;
    }

    private static Map<String, Object> adminDto(AdminUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("email", u.getEmail());
        m.put("role", TenantService.capOf(u));   // effective capability (legacy NULL → auditor)
        m.put("created_at", u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
        return m;   // never the password hash
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
