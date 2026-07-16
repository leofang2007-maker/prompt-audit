package com.gigrt.promptaudit.access;

import com.gigrt.promptaudit.auth.SecurityInterceptor;
import com.gigrt.promptaudit.tenant.TenantService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "who watched the watchers" API (spec 0003). Any authenticated admin — including a {@code viewer}
 * — can inspect their tenant's access log; the platform admin sees all. Tenant-scoped exactly like the
 * prompt list, so an org sees every full-text view/export of ITS data (including views by the platform).
 */
@RestController
@RequestMapping("/api/v1/access-log")
public class AccessLogController {

    private final AccessLogService accessLog;

    public AccessLogController(AccessLogService accessLog) { this.accessLog = accessLog; }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            HttpServletRequest req) {
        String scope = tenantScope(req);
        int p = Math.max(1, page);
        int size = Math.min(Math.max(1, pageSize), 200);
        Page<AdminAccessLog> res = accessLog.list(scope, p - 1, size);

        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminAccessLog a : res.getContent()) items.add(toMap(a));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", res.getTotalElements());
        resp.put("page", p);
        resp.put("page_size", size);
        return resp;
    }

    /** Verify the access-log chain is intact (nobody scrubbed an access record). */
    @GetMapping("/integrity")
    public Map<String, Object> integrity(HttpServletRequest req) {
        return accessLog.verify(tenantScope(req));
    }

    private static Map<String, Object> toMap(AdminAccessLog a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("created_at", str(a.getCreatedAt()));
        m.put("actor_email", a.getActorEmail());
        m.put("actor_role", a.getActorRole());
        m.put("action", a.getAction());
        m.put("target_record_id", a.getTargetRecordId());
        m.put("query_json", a.getQueryJson());
        m.put("reason", a.getReason());
        m.put("ip", a.getIp());
        m.put("tenant_org_id", a.getTenantOrgId());
        m.put("chain_seq", a.getChainSeq());
        return m;
    }

    /** Org admins are locked to their tenant; platform/legacy see all (null). Mirrors AuditController. */
    private static String tenantScope(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        if (p == null || !TenantService.ROLE_ORG.equals(p.get("role"))) return null;
        Object t = p.get("tenant");
        return t == null ? "__no_tenant__" : String.valueOf(t);
    }

    private static String str(Instant i) { return i == null ? null : i.toString(); }
}
