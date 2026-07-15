package com.gigrt.promptaudit.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditController(PromptService service) { this.service = service; }

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
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {

        PromptQuery q = buildQuery(from, to, userEmail, orgId, userUid, repo, sessionId, keyword);
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

    /** Full record incl. prompt text. */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String id) {
        PromptRecord r = service.get(id);
        if (r == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
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
            HttpServletResponse res) throws IOException {

        PromptQuery q = buildQuery(from, to, userEmail, orgId, userUid, repo, sessionId, keyword);
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
        m.put("prompt_preview", PromptService.preview(r.getPrompt()));
        return m;
    }

    private Map<String, Object> toFull(PromptRecord r) {
        Map<String, Object> m = toSummary(r);
        m.remove("prompt_preview");
        m.put("event_id", r.getEventId());
        m.put("user_uid", r.getUserUid());
        m.put("org_id", r.getOrgId());
        m.put("cwd", r.getCwd());
        m.put("transcript_path", r.getTranscriptPath());
        m.put("prompt", r.getPrompt());
        return m;
    }

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
