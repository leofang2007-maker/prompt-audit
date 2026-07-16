package com.gigrt.promptaudit.coverage;

import com.gigrt.promptaudit.auth.SecurityInterceptor;
import com.gigrt.promptaudit.tenant.TenantService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reporting-coverage / gap detection API (spec 0004). Tenant-scoped like the audit list; any admin
 * (viewer or auditor) may read it — coverage is about the control's health, not sensitive content.
 * Host-granular only.
 */
@RestController
@RequestMapping("/api/v1/coverage")
public class CoverageController {

    private final CoverageService coverage;

    public CoverageController(CoverageService coverage) { this.coverage = coverage; }

    @GetMapping
    public Map<String, Object> coverage(HttpServletRequest req) {
        return coverage.compute(tenantScope(req));
    }

    @GetMapping("/roster")
    public Map<String, Object> getRoster(HttpServletRequest req) {
        return Collections.singletonMap("entities", coverage.rosterEntities(tenantScope(req)));
    }

    @PostMapping("/roster")
    public Map<String, Object> setRoster(@RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest req) {
        List<String> entities = extractEntities(body);
        int n = coverage.setRoster(tenantScope(req), entities);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("size", n);
        return resp;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractEntities(Map<String, Object> body) {
        if (body == null) return Collections.emptyList();
        Object e = body.get("entities");
        return e instanceof List ? (List<String>) e : Collections.emptyList();
    }

    /** Org admins are locked to their tenant; platform/legacy see all (null). Mirrors AuditController. */
    private static String tenantScope(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        if (p == null || !TenantService.ROLE_ORG.equals(p.get("role"))) return null;
        Object t = p.get("tenant");
        return t == null ? "__no_tenant__" : String.valueOf(t);
    }
}
