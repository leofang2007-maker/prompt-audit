package com.gigrt.promptaudit.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigrt.promptaudit.access.AccessLogService;
import com.gigrt.promptaudit.auth.SecurityInterceptor;
import com.gigrt.promptaudit.tenant.TenantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin audit API (read-only). Auth = admin session JWT (see SecurityInterceptor);
 * completely separate from the ingest token — a machine that can report cannot read back.
 */
@RestController
@RequestMapping("/api/v1/prompts")
public class AuditController {

    private final PromptService service;
    private final AccessLogService accessLog;
    private final boolean requireReason;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditController(PromptService service, AccessLogService accessLog,
                          @Value("${app.access.require-reason:true}") boolean requireReason) {
        this.service = service;
        this.accessLog = accessLog;
        this.requireReason = requireReason;
    }

    /** Paged, filtered list of audit summaries. */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(name = "user_email", required = false) String userEmail,
            @RequestParam(name = "org_id", required = false) String orgId,
            @RequestParam(name = "user_uid", required = false) String userUid,
            @RequestParam(required = false) String repo,
            @RequestParam(name = "session_id", required = false) String sessionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            HttpServletRequest req) {

        PromptQuery q = buildQuery(from, to, userEmail, orgId, userUid, repo, sessionId, keyword);
        q.tenantOrgId = tenantScope(req);   // org admin ⇒ locked to their tenant; platform ⇒ null (all)
        int p = Math.max(1, page);
        int size = Math.min(Math.max(1, pageSize), 200);   // clamp
        Page<PromptRecord> result = service.list(q, p - 1, size);

        List<Map<String, Object>> items = new ArrayList<>();
        for (PromptRecord r : result.getContent()) items.add(toSummary(r));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", result.getTotalElements());
        resp.put("page", p);
        resp.put("page_size", size);
        return resp;
    }

    /**
     * Full record incl. prompt text. Org admins can only open rows in their own tenant. Revealing the
     * full prompt is gated (spec 0003): a {@code viewer} gets metadata + redacted preview only; an
     * {@code auditor}/platform admin gets full text but must supply a {@code reason}, and the access is
     * itself logged into the tamper-evident access log.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String id,
            @RequestParam(required = false) String reason, HttpServletRequest req) {
        PromptRecord r = service.get(id);
        if (r == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        String scope = tenantScope(req);
        // Don't leak cross-tenant existence — a mismatched tenant looks like "not found".
        if (scope != null && !scope.equals(r.getTenantOrgId())) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        if (!canViewFull(req)) return ResponseEntity.ok(toMasked(r));   // viewer: no full text, not logged
        if (requireReason && isBlank(reason))
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "reason_required"));
        accessLog.record(actorEmail(req), actorRole(req), r.getTenantOrgId(),
                AccessLogService.ACTION_VIEW, r.getId(), null, trim(reason), clientIp(req));
        return ResponseEntity.ok(toFull(r));
    }

    /** Export the current filter set as CSV or JSON (download). */
    @GetMapping("/export")
    public void export(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(name = "user_email", required = false) String userEmail,
            @RequestParam(name = "org_id", required = false) String orgId,
            @RequestParam(name = "user_uid", required = false) String userUid,
            @RequestParam(required = false) String repo,
            @RequestParam(name = "session_id", required = false) String sessionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String reason,
            HttpServletRequest req, HttpServletResponse res) throws IOException {

        // Export is inherently full-text: viewers may not; auditors/platform must give a reason (logged).
        if (!canViewFull(req)) { res.sendError(HttpServletResponse.SC_FORBIDDEN, "export requires auditor role"); return; }
        if (requireReason && isBlank(reason)) { res.sendError(HttpServletResponse.SC_BAD_REQUEST, "reason_required"); return; }

        PromptQuery q = buildQuery(from, to, userEmail, orgId, userUid, repo, sessionId, keyword);
        q.tenantOrgId = tenantScope(req);

        Map<String, Object> desc = new LinkedHashMap<>();
        desc.put("format", format);
        if (from != null) desc.put("from", from);
        if (to != null) desc.put("to", to);
        if (userEmail != null) desc.put("user_email", userEmail);
        if (orgId != null) desc.put("org_id", orgId);
        if (userUid != null) desc.put("user_uid", userUid);
        if (repo != null) desc.put("repo", repo);
        if (sessionId != null) desc.put("session_id", sessionId);
        if (keyword != null) desc.put("keyword", keyword);
        accessLog.record(actorEmail(req), actorRole(req), q.tenantOrgId,
                AccessLogService.ACTION_EXPORT, null, mapper.writeValueAsString(desc), trim(reason), clientIp(req));

        List<PromptRecord> rows = service.forExport(q);
        boolean json = "json".equalsIgnoreCase(format);

        res.setCharacterEncoding("UTF-8");
        res.setHeader("Content-Disposition",
                "attachment; filename=\"prompts-export." + (json ? "json" : "csv") + "\"");

        if (json) {
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            List<Map<String, Object>> full = new ArrayList<>();
            for (PromptRecord r : rows) full.add(toFull(r));
            mapper.writeValue(res.getWriter(), full);
        } else {
            res.setContentType("text/csv; charset=UTF-8");
            PrintWriter w = res.getWriter();
            // UTF-8 BOM so Excel (esp. on macOS) detects the encoding instead of mangling CJK/non-ASCII.
            w.write('\uFEFF');
            CsvWriter.write(w, rows);
            w.flush();
        }
    }

    // ---- mapping (snake_case, mirroring the ingest contract) ----

    private Map<String, Object> toSummary(PromptRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("timestamp", str(r.getTimestamp()));
        m.put("received_at", str(r.getReceivedAt()));
        m.put("session_id", r.getSessionId());
        m.put("user_email", r.getUserEmail());
        m.put("user_name", r.getUserName());
        m.put("org_name", r.getOrgName());
        m.put("repo", r.getRepo());
        m.put("branch", r.getBranch());
        m.put("hostname", r.getHostname());
        m.put("prompt_length", r.getPromptLength());
        m.put("redaction_count", r.getRedactionCount());
        m.put("redacted_types", r.getRedactedTypes());
        m.put("prompt_preview", PromptService.preview(r.getPrompt()));
        return m;
    }

    /** The tenant a caller is locked to: their tenant id for an ORG admin, null for platform/legacy (all).
     *  Only role=org is isolated (fail closed if its tenant claim is missing); everything else — platform,
     *  and legacy pre-multi-tenant "admin" sessions — sees all. */
    private static String tenantScope(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        if (p == null || !TenantService.ROLE_ORG.equals(p.get("role"))) return null;
        Object t = p.get("tenant");
        return t == null ? "__no_tenant__" : String.valueOf(t);   // fail closed if tenant claim missing
    }

    private Map<String, Object> toFull(PromptRecord r) {
        Map<String, Object> m = toSummary(r);
        m.remove("prompt_preview");
        m.put("tenant_org_id", r.getTenantOrgId());
        m.put("event_id", r.getEventId());
        m.put("user_uid", r.getUserUid());
        m.put("org_id", r.getOrgId());
        m.put("cwd", r.getCwd());
        m.put("transcript_path", r.getTranscriptPath());
        m.put("prompt", r.getPrompt());
        m.put("prompt_hidden", false);
        return m;
    }

    /** What a {@code viewer} sees on detail: everything except the full prompt text (spec 0003). */
    private Map<String, Object> toMasked(PromptRecord r) {
        Map<String, Object> m = toSummary(r);        // keeps prompt_preview + redaction metadata
        m.put("tenant_org_id", r.getTenantOrgId());
        m.put("event_id", r.getEventId());
        m.put("user_uid", r.getUserUid());
        m.put("org_id", r.getOrgId());
        m.put("cwd", r.getCwd());
        m.put("transcript_path", r.getTranscriptPath());
        m.put("prompt", null);
        m.put("prompt_hidden", true);
        return m;
    }

    // ---- access gating (spec 0003) ----

    /** True for the platform superadmin (or legacy "admin") and for org admins with the auditor role. */
    private static boolean canViewFull(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        if (p == null) return false;
        Object role = p.get("role");
        if (TenantService.ROLE_PLATFORM.equals(role) || "admin".equals(role)) return true;
        return TenantService.CAP_AUDITOR.equals(p.get("cap"));
    }

    private static String actorEmail(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        return p == null ? null : str2(p.get("sub"));
    }

    /** Effective full-access role recorded in the log: "platform" or "auditor". */
    private static String actorRole(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        Object role = p == null ? null : p.get("role");
        return (TenantService.ROLE_PLATFORM.equals(role) || "admin".equals(role)) ? "platform" : "auditor";
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String trim(String s) { return s == null ? null : s.trim(); }
    private static String str2(Object o) { return o == null ? null : String.valueOf(o); }

    private static String str(Instant i) { return i == null ? null : i.toString(); }

    private PromptQuery buildQuery(String from, String to, String userEmail, String orgId, String userUid,
                                   String repo, String sessionId, String keyword) {
        PromptQuery q = new PromptQuery();
        q.from = parseTs(from);
        q.to = parseTs(to);
        q.userEmail = userEmail;
        q.orgId = orgId;
        q.userUid = userUid;
        q.repo = repo;
        q.sessionId = sessionId;
        q.keyword = keyword;
        return q;
    }

    /** Lenient RFC3339 parse for range filters; null when blank/unparseable. */
    private static Instant parseTs(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return OffsetDateTime.parse(s.trim()).toInstant(); } catch (Exception ignore) {}
        try { return Instant.parse(s.trim()); } catch (Exception ignore) {}
        return null;
    }
}
