package com.gigrt.promptaudit.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigrt.promptaudit.access.AccessLogService;
import com.gigrt.promptaudit.access.AdminAccessLogRepository;
import com.gigrt.promptaudit.audit.PromptRepository;
import com.gigrt.promptaudit.audit.PromptService;
import com.gigrt.promptaudit.coverage.CoverageService;
import com.gigrt.promptaudit.tenant.AdminUser;
import com.gigrt.promptaudit.tenant.TenantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Audit-ready evidence pack (spec 0007). Aggregates the operating controls — tamper-evident storage
 * (#1), the admin access log (#3), coverage (#4), and redaction (#2) — plus record counts and a config
 * attestation into one tenant-scoped, point-in-time bundle. Summary/counts/hashes ONLY: it carries no
 * raw prompt text, so the evidence pack is not itself a secret-hoard. A {@code bundle_hash} plus the
 * embedded chain head hashes make it tamper-evident (re-running yields the same anchors iff nothing
 * changed).
 */
@Service
public class EvidenceService {

    private final PromptService promptService;
    private final PromptRepository prompts;
    private final AccessLogService accessLogService;
    private final AdminAccessLogRepository accessLogs;
    private final CoverageService coverageService;
    private final TenantService tenants;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String redactionMode;
    private final boolean requireReason;

    public EvidenceService(PromptService promptService, PromptRepository prompts,
                           AccessLogService accessLogService, AdminAccessLogRepository accessLogs,
                           CoverageService coverageService, TenantService tenants,
                           @Value("${app.redaction.mode:mask}") String redactionMode,
                           @Value("${app.access.require-reason:true}") boolean requireReason) {
        this.promptService = promptService;
        this.prompts = prompts;
        this.accessLogService = accessLogService;
        this.accessLogs = accessLogs;
        this.coverageService = coverageService;
        this.tenants = tenants;
        this.redactionMode = redactionMode;
        this.requireReason = requireReason;
    }

    /** Build the evidence bundle for a tenant (scope=null ⇒ platform, all) over [from, to]. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> build(String scope, Instant from, Instant to) {
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("from", from.toString());
        period.put("to", to.toString());
        content.put("period", period);
        content.put("tenant", scope);

        // #1 tamper-evident storage — includes head hashes (the anchor)
        content.put("integrity", promptService.verify(scope));

        // #3 admin access log — summary + chain anchor
        Map<String, Object> aclVerify = accessLogService.verify(scope);
        List<String> aclHeads = new ArrayList<>();
        Object aclChains = aclVerify.get("chains");
        if (aclChains instanceof List) {
            for (Object c : (List<Object>) aclChains) {
                if (c instanceof Map) aclHeads.add(String.valueOf(((Map<String, Object>) c).get("head_hash")));
            }
        }
        Map<String, Object> byAction = new LinkedHashMap<>();
        byAction.put("view_detail", accessLogs.countByActionInPeriod(scope, "view_detail", from, to));
        byAction.put("export", accessLogs.countByActionInPeriod(scope, "export", from, to));
        byAction.put("evidence", accessLogs.countByActionInPeriod(scope, "evidence", from, to));
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("total", accessLogs.countInPeriod(scope, from, to));
        access.put("by_action", byAction);
        access.put("chain_ok", aclVerify.get("ok"));
        access.put("head_hashes", aclHeads);
        content.put("access_log", access);

        // #4 coverage snapshot
        Map<String, Object> cov = coverageService.compute(scope);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("total_hosts", cov.get("total_hosts"));
        coverage.put("active_hosts", cov.get("active_hosts"));
        coverage.put("went_dark", sizeOf(cov.get("silent")));
        coverage.put("never_reported", sizeOf(cov.get("never_reported")));
        content.put("coverage", coverage);

        // #2 redaction stats (by_type parsed from the comma-joined redacted_types)
        Map<String, Integer> byType = new TreeMap<>();
        for (String types : prompts.redactedTypesInPeriod(scope, from, to)) {
            if (types == null || types.isEmpty()) continue;
            for (String t : types.split(",")) {
                String k = t.trim();
                if (!k.isEmpty()) byType.merge(k, 1, Integer::sum);
            }
        }
        Map<String, Object> redaction = new LinkedHashMap<>();
        redaction.put("records_with_redactions", prompts.countRedactedInPeriod(scope, from, to));
        redaction.put("secrets_masked", prompts.sumRedactionsInPeriod(scope, from, to));
        redaction.put("by_type", byType);
        content.put("redaction", redaction);

        // record counts
        Map<String, Object> records = new LinkedHashMap<>();
        records.put("total_in_period", prompts.countInPeriod(scope, from, to));
        records.put("total_all_time", prompts.countAllForScope(scope));
        content.put("records", records);

        // config attestation
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("redaction_mode", redactionMode);
        config.put("redaction_enabled", !"off".equalsIgnoreCase(redactionMode));
        config.put("reason_required_for_full_text", requireReason);
        config.put("retention", "indefinite — append-only, tamper-evident (hash-chained)");
        config.put("auth_model", "two schemes: write-only ingest token + read-only admin session JWT");
        config.put("roles", Arrays.asList("viewer", "auditor", "platform"));
        content.put("config", config);

        // admins & roles (org scope only)
        if (scope != null) {
            List<Map<String, Object>> admins = new ArrayList<>();
            for (AdminUser u : tenants.listAdmins(scope)) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("email", u.getEmail());
                a.put("role", TenantService.capOf(u));
                admins.add(a);
            }
            content.put("admins", admins);
        }

        // assemble: generated_at + content + self-hash (hash covers content only, so it's
        // deterministic for identical underlying state — generated_at is excluded).
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("generated_at", Instant.now().toString());
        resp.putAll(content);
        resp.put("bundle_hash", sha256(canonical(content)));
        return resp;
    }

    private static int sizeOf(Object list) { return list instanceof List ? ((List<?>) list).size() : 0; }

    private String canonical(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException("evidence canonicalization failed", e); }
    }

    private static String sha256(String s) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] d = sha.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException("sha256 failed", e); }
    }
}
