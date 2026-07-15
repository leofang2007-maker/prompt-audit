package com.gigrt.promptaudit.ingest;

import com.gigrt.promptaudit.audit.PromptRecord;
import com.gigrt.promptaudit.audit.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ingest endpoint for client hooks. Auth = INGEST_TOKEN (see SecurityInterceptor).
 * Must return fast; logs metadata + prompt LENGTH only — never the token or prompt text.
 */
@RestController
@RequestMapping("/api/v1/prompts")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final PromptService service;

    public IngestController(PromptService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody(required = false) IngestRequest body,
                                                       HttpServletRequest req) {
        if (body == null || body.prompt == null || body.prompt.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Collections.singletonMap("error", "prompt_required"));
        }

        PromptRecord r = new PromptRecord();
        // Trusted owning tenant from the ingest token (set by SecurityInterceptor) — NOT the client's claim.
        r.setTenantOrgId((String) req.getAttribute(
                com.gigrt.promptaudit.auth.SecurityInterceptor.INGEST_TENANT));
        r.setEventId(trimToNull(body.event_id));
        r.setTimestamp(parseTs(body.timestamp));
        r.setSessionId(trimToNull(body.session_id));
        r.setUserEmail(trimToNull(body.user_email));
        r.setUserName(trimToNull(body.user_name));
        r.setUserUid(trimToNull(body.user_uid));
        r.setOrgId(trimToNull(body.org_id));
        r.setOrgName(trimToNull(body.org_name));
        r.setRepo(trimToNull(body.repo));
        r.setBranch(trimToNull(body.branch));
        r.setCwd(trimToNull(body.cwd));
        r.setTranscriptPath(trimToNull(body.transcript_path));
        r.setHostname(trimToNull(body.hostname));
        r.setPrompt(body.prompt);

        PromptService.IngestResult res = service.ingest(r);
        PromptRecord saved = res.record;

        // Safe log line: no token, no prompt text — only length + non-sensitive context.
        log.info("ingest {} id={} event_id={} user={} org={} repo={} session={} promptLen={}",
                res.deduplicated ? "dedup" : "ok", saved.getId(), saved.getEventId(),
                saved.getUserEmail(), saved.getOrgName(), saved.getRepo(), saved.getSessionId(),
                saved.getPromptLength());

        // Same logical submission always maps to one id; a duplicate returns the ORIGINAL id (200),
        // with deduplicated:true so client retries can tell it landed without leaking a new id.
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("id", saved.getId());
        if (res.deduplicated) resp.put("deduplicated", true);
        return ResponseEntity.ok(resp);
    }

    /** Lenient RFC3339 parse: accepts offsets and plain instants; returns null on anything unparseable. */
    private static Instant parseTs(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return OffsetDateTime.parse(s.trim()).toInstant(); } catch (Exception ignore) {}
        try { return Instant.parse(s.trim()); } catch (Exception ignore) {}
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
