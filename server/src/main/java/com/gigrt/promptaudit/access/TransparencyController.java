package com.gigrt.promptaudit.access;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Developer-facing transparency disclosure (spec 0003). Deliberately UNAUTHENTICATED — anyone whose
 * prompts are captured can read exactly what this deployment records, that secrets are redacted, that
 * admin access is itself logged, and that no per-developer productivity scoring is computed. Turns
 * "enable, don't surveil" from a promise into something an org can point developers at.
 */
@RestController
@RequestMapping("/api/v1/transparency")
public class TransparencyController {

    private final String redactionMode;
    private final boolean requireReason;

    public TransparencyController(@Value("${app.redaction.mode:mask}") String redactionMode,
                                  @Value("${app.access.require-reason:true}") boolean requireReason) {
        this.redactionMode = redactionMode;
        this.requireReason = requireReason;
    }

    @GetMapping
    public Map<String, Object> disclose() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("captured_fields", Arrays.asList(
                "timestamp", "session_id", "user_email", "user_name", "org_name",
                "repo", "branch", "cwd", "hostname", "transcript_path", "prompt (secret-redacted)"));

        Map<String, Object> redaction = new LinkedHashMap<>();
        redaction.put("enabled", !"off".equalsIgnoreCase(redactionMode));
        redaction.put("mode", redactionMode);
        redaction.put("note", "Well-formed secrets are masked before storage; the raw secret is not kept.");
        m.put("redaction", redaction);

        Map<String, Object> access = new LinkedHashMap<>();
        access.put("full_text_view_logged", true);
        access.put("export_logged", true);
        access.put("reason_required", requireReason);
        access.put("tamper_evident", true);
        access.put("note", "Every reveal of your full prompt text by an admin is itself recorded in a "
                + "hash-chained access log, visible to your organization.");
        m.put("admin_access", access);

        m.put("retention", "Indefinite by default — records are append-only and tamper-evident "
                + "(hash-chained); they cannot be silently edited or deleted.");
        m.put("productivity_scoring", "none — no per-developer score, ranking, or performance metric "
                + "is computed or exposed by this system.");
        m.put("roles", Arrays.asList("viewer (metadata + redacted previews)", "auditor (full text, logged)"));
        return m;
    }
}
