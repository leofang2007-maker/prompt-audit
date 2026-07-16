package com.gigrt.promptaudit.audit;

import com.gigrt.promptaudit.auth.SecurityInterceptor;
import com.gigrt.promptaudit.tenant.TenantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Tamper-evident chain verification (spec 0001). Admin only (wired in WebConfig).
 * Org admins verify their own tenant's chain; the platform admin verifies every chain.
 */
@RestController
@RequestMapping("/api/v1/integrity")
public class IntegrityController {

    private final PromptService service;

    public IntegrityController(PromptService service) { this.service = service; }

    @GetMapping
    public Map<String, Object> verify(HttpServletRequest req) {
        return service.verify(tenantScope(req));
    }

    /** Org admin → locked to their tenant chain; platform → null (all chains). */
    private static String tenantScope(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        if (p == null || !TenantService.ROLE_ORG.equals(p.get("role"))) return null;
        Object t = p.get("tenant");
        return t == null ? "__no_tenant__" : String.valueOf(t);
    }
}
