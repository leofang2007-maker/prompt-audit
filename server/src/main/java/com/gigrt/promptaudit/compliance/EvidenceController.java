package com.gigrt.promptaudit.compliance;

import com.gigrt.promptaudit.access.AccessLogService;
import com.gigrt.promptaudit.auth.SecurityInterceptor;
import com.gigrt.promptaudit.tenant.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;

/**
 * Audit-ready evidence pack API (spec 0007). Auditor/platform only (viewers denied); generating a pack
 * is itself written to the access log — producing evidence is part of the evidence. Tenant-scoped.
 */
@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {

    private static final long DEFAULT_DAYS = 90;

    private final EvidenceService evidence;
    private final AccessLogService accessLog;

    public EvidenceController(EvidenceService evidence, AccessLogService accessLog) {
        this.evidence = evidence;
        this.accessLog = accessLog;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> evidence(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String reason,
            HttpServletRequest req) {

        if (!canViewFull(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Collections.singletonMap("error", "evidence requires the auditor role"));
        }
        Instant toI = parseTs(to, Instant.now());
        Instant fromI = parseTs(from, toI.minus(DEFAULT_DAYS, ChronoUnit.DAYS));
        String scope = tenantScope(req);

        // generating evidence is a privileged compliance action → record it (no raw prompt text involved)
        accessLog.record(actorEmail(req), actorRole(req), scope, "evidence", null,
                "{\"from\":\"" + fromI + "\",\"to\":\"" + toI + "\"}", trim(reason), clientIp(req));

        return ResponseEntity.ok(evidence.build(scope, fromI, toI));
    }

    // ---- helpers (mirror AuditController) ----

    private static boolean canViewFull(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        if (p == null) return false;
        Object role = p.get("role");
        if (TenantService.ROLE_PLATFORM.equals(role) || "admin".equals(role)) return true;
        return TenantService.CAP_AUDITOR.equals(p.get("cap"));
    }

    private static String actorEmail(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        return p == null ? null : (p.get("sub") == null ? null : String.valueOf(p.get("sub")));
    }

    private static String actorRole(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        Object role = p == null ? null : p.get("role");
        return (TenantService.ROLE_PLATFORM.equals(role) || "admin".equals(role)) ? "platform" : "auditor";
    }

    private static String tenantScope(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        if (p == null || !TenantService.ROLE_ORG.equals(p.get("role"))) return null;
        Object t = p.get("tenant");
        return t == null ? "__no_tenant__" : String.valueOf(t);
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }

    private static Instant parseTs(String s, Instant dflt) {
        if (s == null || s.trim().isEmpty()) return dflt;
        try { return OffsetDateTime.parse(s.trim()).toInstant(); } catch (Exception ignore) {}
        try { return Instant.parse(s.trim()); } catch (Exception ignore) {}
        return dflt;
    }
}
