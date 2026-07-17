package com.gigrt.promptaudit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigrt.promptaudit.audit.PromptRecord;
import com.gigrt.promptaudit.audit.PromptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Covers the demo's headline security property: the ingest token and the admin session are
 * two separate schemes, and the read side is closed to anything but a valid admin JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PromptAuditIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PromptRepository promptRepo;
    @Autowired com.gigrt.promptaudit.compliance.EvidenceService evidenceService;

    private static final String INGEST = "Bearer test-ingest-token";

    @Test
    void ingest_requires_token() throws Exception {
        mvc.perform(post("/api/v1/prompts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ingest_rejects_missing_prompt() throws Exception {
        mvc.perform(post("/api/v1/prompts").header("Authorization", INGEST)
                .contentType(MediaType.APPLICATION_JSON).content("{\"repo\":\"acme/api\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ingest_token_cannot_read_audit_log() throws Exception {
        // Even a valid ingest token must NOT be able to list/read audit records.
        mvc.perform(get("/api/v1/prompts").header("Authorization", INGEST))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void full_flow_ingest_then_admin_read_and_export() throws Exception {
        // 1) client hook reports a prompt
        MvcResult ing = mvc.perform(post("/api/v1/prompts").header("Authorization", INGEST)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"timestamp\":\"2026-07-15T10:00:00Z\",\"user_email\":\"dev@acme.com\","
                        + "\"repo\":\"acme/api\",\"prompt\":\"refactor the auth module\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn();
        String id = json.readTree(ing.getResponse().getContentAsString()).get("id").asText();

        // 2) admin logs in → JWT
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@promptaudit.local\",\"password\":\"secret-admin\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = json.readTree(login.getResponse().getContentAsString()).get("token").asText();
        String auth = "Bearer " + token;

        // 3) admin lists + filters by keyword
        mvc.perform(get("/api/v1/prompts?keyword=refactor").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].user_email").value("dev@acme.com"))
                .andExpect(jsonPath("$.items[0].prompt_preview").value("refactor the auth module"));

        // 4) admin reads full detail (includes prompt text) — full-text view needs a reason (spec 0003)
        mvc.perform(get("/api/v1/prompts/" + id + "?reason=incident-check").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value("refactor the auth module"));

        // 5) admin exports CSV for the current filter (reason required)
        MvcResult csv = mvc.perform(get("/api/v1/prompts/export?format=csv&keyword=refactor&reason=quarterly-audit")
                .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("prompts-export.csv")))
                .andReturn();
        String csvBody = csv.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(csvBody.contains("dev@acme.com"));
        // Starts with a UTF-8 BOM so Excel reads non-ASCII (Chinese) correctly.
        org.junit.jupiter.api.Assertions.assertTrue(csvBody.startsWith("\uFEFF"));
    }

    @Test
    void duplicate_event_id_is_idempotent() throws Exception {
        // Byte-identical payload (same event_id) delivered twice — the IDE double-fire / drain retry case.
        String body = "{\"event_id\":\"" + sha(1) + "\",\"timestamp\":\"2026-07-15T09:41:00Z\","
                + "\"session_id\":\"sess-dup\",\"prompt\":\"add idempotency keys\"}";

        MvcResult first = mvc.perform(post("/api/v1/prompts").header("Authorization", INGEST)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        String id1 = json.readTree(first.getResponse().getContentAsString()).get("id").asText();

        MvcResult second = mvc.perform(post("/api/v1/prompts").header("Authorization", INGEST)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id1))          // SAME id, not a new pr_
                .andExpect(jsonPath("$.deduplicated").value(true))
                .andReturn();

        // Admin sees exactly ONE row for this event.
        String token = adminToken();
        mvc.perform(get("/api/v1/prompts?session_id=sess-dup").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void different_event_id_creates_two_rows() throws Exception {
        String base = "{\"event_id\":\"%s\",\"session_id\":\"sess-diff\",\"prompt\":\"p-%s\"}";
        mvc.perform(post("/api/v1/prompts").header("Authorization", INGEST)
                .contentType(MediaType.APPLICATION_JSON).content(String.format(base, sha(2), "1")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/prompts").header("Authorization", INGEST)
                .contentType(MediaType.APPLICATION_JSON).content(String.format(base, sha(3), "2")))
                .andExpect(status().isOk());

        String token = adminToken();
        mvc.perform(get("/api/v1/prompts?session_id=sess-diff").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    /** A distinct 64-char lowercase-hex event_id per seed digit (Java 8: no String.repeat). */
    private static String sha(int seed) {
        char[] c = new char[64];
        java.util.Arrays.fill(c, Character.forDigit(seed, 16));
        return new String(c);
    }

    @Test
    void v102_fields_persist_and_are_filterable() throws Exception {
        // The handoff's real v1.0.2 sample payload (14 fields incl. identity + transcript_path).
        String payload = "{\"event_id\":\"833017a9-39eb-4c85-8220-2d4110fb3576\","
                + "\"timestamp\":\"2026-07-15T10:23:33Z\",\"session_id\":\"task-a64e.session.execution\","
                + "\"user_email\":\"larry.fs@alibaba-inc.com\",\"user_name\":\"larry.fs\","
                + "\"user_uid\":\"019f25ea-c7c9-72dc-9ff1-e157bf1aff20\","
                + "\"org_id\":\"019f21f9-2e3e-7286-b6ee-79a834cf0c56\",\"org_name\":\"enterprise_pdsa\","
                + "\"repo\":\"prompt-audit\",\"branch\":\"feature/x\",\"cwd\":\"/Users/larry/Documents\","
                + "\"transcript_path\":\"/Users/larry/.qoder/projects/x/task.jsonl\","
                + "\"hostname\":\"wxks-Mac-mini.local\",\"prompt\":\"hello audit\"}";

        MvcResult ing = mvc.perform(post("/api/v1/prompts").header("Authorization", INGEST)
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn();
        String id = json.readTree(ing.getResponse().getContentAsString()).get("id").asText();

        String token = adminToken();

        // Detail persists all 5 new columns (not silently dropped). Platform view needs a reason (0003).
        mvc.perform(get("/api/v1/prompts/" + id + "?reason=field-check").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_name").value("larry.fs"))
                .andExpect(jsonPath("$.user_uid").value("019f25ea-c7c9-72dc-9ff1-e157bf1aff20"))
                .andExpect(jsonPath("$.org_id").value("019f21f9-2e3e-7286-b6ee-79a834cf0c56"))
                .andExpect(jsonPath("$.org_name").value("enterprise_pdsa"))
                .andExpect(jsonPath("$.transcript_path").value("/Users/larry/.qoder/projects/x/task.jsonl"));

        // List shows compliance-attribution fields + org_id is an exact filter.
        mvc.perform(get("/api/v1/prompts?org_id=019f21f9-2e3e-7286-b6ee-79a834cf0c56")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].user_name").value("larry.fs"))
                .andExpect(jsonPath("$.items[0].org_name").value("enterprise_pdsa"));
    }

    @Test
    void old_9_field_payload_still_ingests() throws Exception {
        // Un-upgraded client (no identity fields) must still be accepted — fail-open, columns null.
        mvc.perform(post("/api/v1/prompts").header("Authorization", INGEST)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_id\":\"legacy-uuid-1\",\"user_email\":\"old@acme.com\",\"prompt\":\"legacy hook\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void multi_tenant_isolation() throws Exception {
        String platform = "Bearer " + adminToken();

        // platform creates two tenants (each gets its own ingest token)
        String acmeTok = createTenant(platform, "Acme");
        String betaTok = createTenant(platform, "Beta");
        org.junit.jupiter.api.Assertions.assertTrue(acmeTok.startsWith("iat_"));

        // each org's token stamps its rows with the TRUSTED tenant (not client-claimed org)
        ingest(acmeTok, "acme secret plan", "acme-sess");
        ingest(betaTok, "beta roadmap", "beta-sess");

        // platform provisions an Acme org-admin and logs in as them
        mvc.perform(post("/api/v1/tenants/" + tenantIdByName(platform, "Acme") + "/admins")
                        .header("Authorization", platform).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@acme.com\",\"password\":\"acme-pw-123\"}"))
                .andExpect(status().isCreated());
        String acmeAdmin = "Bearer " + login("admin@acme.com", "acme-pw-123");

        // the Acme admin sees ONLY Acme's row — Beta's is invisible
        mvc.perform(get("/api/v1/prompts").header("Authorization", acmeAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].session_id").value("acme-sess"));

        // a keyword that only matches Beta's row returns nothing for the Acme admin
        mvc.perform(get("/api/v1/prompts?keyword=roadmap").header("Authorization", acmeAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        // org admin cannot reach platform tenant management (403)
        mvc.perform(get("/api/v1/tenants").header("Authorization", acmeAdmin))
                .andExpect(status().isForbidden());

        // org admin views + rotates its OWN token; the old token stops authorizing
        MvcResult mine = mvc.perform(get("/api/v1/my/tenant").header("Authorization", acmeAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Acme")).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(acmeTok,
                json.readTree(mine.getResponse().getContentAsString()).get("token").asText());
        mvc.perform(post("/api/v1/my/tenant/rotate-token").header("Authorization", acmeAdmin))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/prompts").header("Authorization", "Bearer " + acmeTok)
                .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"with old token\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenant_admin_routes_require_auth_and_platform_role() throws Exception {
        mvc.perform(get("/api/v1/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isUnauthorized());
    }

    // ---- tamper-evident chain (spec 0001) ----

    @Test
    void chain_verifies_ok_for_a_tenant() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "ChainOk");
        String tid = tenantIdByName(platform, "ChainOk");
        ingest(tok, "first prompt", "c-ok-1");
        ingest(tok, "second prompt", "c-ok-2");
        ingest(tok, "third prompt", "c-ok-3");

        mvc.perform(post("/api/v1/tenants/" + tid + "/admins").header("Authorization", platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@chainok.example\",\"password\":\"chain-ok-pw\"}"))
                .andExpect(status().isCreated());
        String orgAuth = "Bearer " + login("admin@chainok.example", "chain-ok-pw");

        mvc.perform(get("/api/v1/integrity").header("Authorization", orgAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.chains[0].checked").value(3))
                .andExpect(jsonPath("$.chains[0].first_broken_id").doesNotExist());
    }

    @Test
    void tampering_breaks_the_chain() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "ChainTamper");
        String tid = tenantIdByName(platform, "ChainTamper");
        ingest(tok, "clean one", "c-t-1");
        ingest(tok, "clean two", "c-t-2");

        // tamper: edit a stored prompt directly in the DB (record_hash left unchanged → mismatch).
        List<PromptRecord> rows = promptRepo.findByTenantOrgIdOrderByChainSeqAsc(tid);
        PromptRecord victim = rows.get(0);
        victim.setPrompt("SILENTLY EDITED");
        promptRepo.save(victim);

        String orgAuth = "Bearer " + platformCreatedOrgAdmin(platform, tid, "admin@chaintamper.example", "chain-t-pw");
        mvc.perform(get("/api/v1/integrity").header("Authorization", orgAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.chains[0].first_broken_id").value(victim.getId()));
    }

    @Test
    void deduped_repeat_adds_no_chain_link() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "ChainDedup");
        String tid = tenantIdByName(platform, "ChainDedup");
        String body = "{\"event_id\":\"" + sha(9) + "\",\"prompt\":\"only once\"}";
        for (int i = 0; i < 2; i++)
            mvc.perform(post("/api/v1/prompts").header("Authorization", "Bearer " + tok)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        // exactly one chained record for this tenant
        org.junit.jupiter.api.Assertions.assertEquals(1, promptRepo.findByTenantOrgIdOrderByChainSeqAsc(tid).size());
    }

    // ---- secret redaction at capture (spec 0002) ----

    @Test
    void secrets_are_masked_before_storage_and_chain_still_verifies() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "Redact");
        String tid = tenantIdByName(platform, "Redact");

        // A prompt carrying three distinct, well-formed secrets.
        ingest(tok, "deploy with AKIAIOSFODNN7EXAMPLE and "
                + "ghp_abcdefghijklmnopqrstuvwxyzABCDEFGHIJ and password=hunter2supersecret done", "redact-sess");

        PromptRecord rec = promptRepo.findByTenantOrgIdOrderByChainSeqAsc(tid).get(0);
        // Each secret is replaced by its typed token…
        org.junit.jupiter.api.Assertions.assertTrue(rec.getPrompt().contains("[REDACTED:aws_key]"));
        org.junit.jupiter.api.Assertions.assertTrue(rec.getPrompt().contains("[REDACTED:github_token]"));
        org.junit.jupiter.api.Assertions.assertTrue(rec.getPrompt().contains("password=[REDACTED:credential]"));
        // …and the raw secret is NOT hoarded in the store.
        org.junit.jupiter.api.Assertions.assertFalse(rec.getPrompt().contains("AKIAIOSFODNN7EXAMPLE"));
        org.junit.jupiter.api.Assertions.assertFalse(rec.getPrompt().contains("hunter2supersecret"));
        // Evidence is kept: how many, and which types (sorted, deduped).
        org.junit.jupiter.api.Assertions.assertEquals(3, rec.getRedactionCount());
        org.junit.jupiter.api.Assertions.assertEquals("aws_key,credential,github_token", rec.getRedactedTypes());

        // Redaction is deterministic, so the tamper-evident chain (spec 0001) still verifies.
        String orgAuth = "Bearer " + platformCreatedOrgAdmin(platform, tid, "admin@redact.example", "redact-pw-1");
        mvc.perform(get("/api/v1/integrity").header("Authorization", orgAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // Admin detail surfaces the redaction metadata.
        mvc.perform(get("/api/v1/prompts/" + rec.getId()).header("Authorization", orgAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redaction_count").value(3))
                .andExpect(jsonPath("$.redacted_types").value("aws_key,credential,github_token"));
    }

    @Test
    void clean_prompt_is_stored_verbatim() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "NoRedact");
        String tid = tenantIdByName(platform, "NoRedact");

        String clean = "refactor the auth module and improve error handling";
        ingest(tok, clean, "clean-sess");

        PromptRecord rec = promptRepo.findByTenantOrgIdOrderByChainSeqAsc(tid).get(0);
        org.junit.jupiter.api.Assertions.assertEquals(clean, rec.getPrompt());   // untouched — no false positive
        org.junit.jupiter.api.Assertions.assertEquals(0, rec.getRedactionCount());
    }

    // ---- anti-surveillance guardrails (spec 0003) ----

    @Test
    void viewer_cannot_see_full_text_auditor_can_with_reason() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "Roles");
        String tid = tenantIdByName(platform, "Roles");
        ingest(tok, "role test prompt", "role-sess");
        String recId = promptRepo.findByTenantOrgIdOrderByChainSeqAsc(tid).get(0).getId();

        // viewer: full text withheld, no reason needed (nothing is revealed → not logged)
        String viewer = "Bearer " + createAdminWithRole(platform, tid, "viewer@roles.example", "vpw-12345", "viewer");
        mvc.perform(get("/api/v1/prompts/" + recId).header("Authorization", viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt_hidden").value(true))
                .andExpect(jsonPath("$.prompt").value(org.hamcrest.Matchers.nullValue()));

        String auditor = "Bearer " + createAdminWithRole(platform, tid, "auditor@roles.example", "apw-12345", "auditor");
        // auditor WITHOUT reason → rejected
        mvc.perform(get("/api/v1/prompts/" + recId).header("Authorization", auditor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("reason_required"));
        // auditor WITH reason → full text
        mvc.perform(get("/api/v1/prompts/" + recId + "?reason=security-review").header("Authorization", auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt_hidden").value(false))
                .andExpect(jsonPath("$.prompt").value("role test prompt"));
    }

    @Test
    void export_forbidden_for_viewer_and_reason_required_for_auditor() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "Roles2");
        String tid = tenantIdByName(platform, "Roles2");
        ingest(tok, "exportable", "exp-sess");

        String viewer = "Bearer " + createAdminWithRole(platform, tid, "viewer@roles2.example", "vpw-12345", "viewer");
        mvc.perform(get("/api/v1/prompts/export?format=csv&reason=x").header("Authorization", viewer))
                .andExpect(status().isForbidden());

        String auditor = "Bearer " + createAdminWithRole(platform, tid, "auditor@roles2.example", "apw-12345", "auditor");
        mvc.perform(get("/api/v1/prompts/export?format=csv").header("Authorization", auditor))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/prompts/export?format=csv&reason=quarterly").header("Authorization", auditor))
                .andExpect(status().isOk());
    }

    @Test
    void full_text_views_are_logged_and_the_access_chain_verifies() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "AclChain");
        String tid = tenantIdByName(platform, "AclChain");
        ingest(tok, "watched prompt", "acl-sess");
        String recId = promptRepo.findByTenantOrgIdOrderByChainSeqAsc(tid).get(0).getId();

        String auditor = "Bearer " + createAdminWithRole(platform, tid, "auditor@acl.example", "apw-12345", "auditor");
        mvc.perform(get("/api/v1/prompts/" + recId + "?reason=looking-into-incident-42").header("Authorization", auditor))
                .andExpect(status().isOk());

        // the view is in the access log, scoped to the org whose data was viewed
        mvc.perform(get("/api/v1/access-log").header("Authorization", auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].action").value("view_detail"))
                .andExpect(jsonPath("$.items[0].target_record_id").value(recId))
                .andExpect(jsonPath("$.items[0].reason").value("looking-into-incident-42"))
                .andExpect(jsonPath("$.items[0].actor_role").value("auditor"));

        // and the access log itself is tamper-evident
        mvc.perform(get("/api/v1/access-log/integrity").header("Authorization", auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void platform_admin_views_are_logged_no_silent_bypass() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "PlatView");
        String tid = tenantIdByName(platform, "PlatView");
        ingest(tok, "platform will peek", "pv-sess");
        String recId = promptRepo.findByTenantOrgIdOrderByChainSeqAsc(tid).get(0).getId();

        // the platform superadmin reveals full text — must supply a reason and IS logged
        mvc.perform(get("/api/v1/prompts/" + recId + "?reason=platform-spot-check").header("Authorization", platform))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value("platform will peek"));

        // the org's own auditor sees that the platform looked at their data
        String auditor = "Bearer " + createAdminWithRole(platform, tid, "auditor@pv.example", "apw-12345", "auditor");
        mvc.perform(get("/api/v1/access-log").header("Authorization", auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].actor_role").value("platform"));
    }

    @Test
    void transparency_endpoint_is_public() throws Exception {
        mvc.perform(get("/api/v1/transparency"))          // NO auth header
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productivity_scoring").exists())
                .andExpect(jsonPath("$.admin_access.reason_required").value(true))
                .andExpect(jsonPath("$.admin_access.full_text_view_logged").value(true))
                .andExpect(jsonPath("$.redaction.mode").value("mask"));
    }

    @Test
    void admin_role_can_be_assigned_and_changed() throws Exception {
        String platform = "Bearer " + adminToken();
        createTenant(platform, "RoleMgmt");
        String tid = tenantIdByName(platform, "RoleMgmt");

        // created as auditor…
        MvcResult created = mvc.perform(post("/api/v1/tenants/" + tid + "/admins").header("Authorization", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@rolemgmt.example\",\"password\":\"pw-123456\",\"role\":\"auditor\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("auditor"))
                .andReturn();
        String aid = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        // …then downgraded to viewer
        mvc.perform(post("/api/v1/tenants/" + tid + "/admins/" + aid + "/role").header("Authorization", platform)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"viewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("viewer"));

        // an org admin (viewer) cannot reach platform role management (403)
        String viewer = "Bearer " + login("a@rolemgmt.example", "pw-123456");
        mvc.perform(post("/api/v1/tenants/" + tid + "/admins/" + aid + "/role").header("Authorization", viewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"auditor\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- reporting-coverage / gap detection (spec 0004) ----

    @Test
    void coverage_flags_went_dark_hosts_and_counts_active() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "Coverage");
        String tid = tenantIdByName(platform, "Coverage");
        java.time.Instant now = java.time.Instant.now();

        // deadhost: 6 daily reports ending 10 days ago → cadence ~1d, silent 10d ⇒ went-dark
        for (int d = 15; d >= 10; d--) seed(tid, "deadhost.local", now.minus(java.time.Duration.ofDays(d)));
        // livehost: recent reports ⇒ active (and too little history to ever be flagged)
        seed(tid, "livehost.local", now.minus(java.time.Duration.ofHours(2)));
        seed(tid, "livehost.local", now.minus(java.time.Duration.ofHours(1)));
        seed(tid, "livehost.local", now.minus(java.time.Duration.ofMinutes(5)));

        String admin = "Bearer " + createAdminWithRole(platform, tid, "cov@coverage.example", "cov-pw-1234", "viewer");
        mvc.perform(get("/api/v1/coverage").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_hosts").value(2))
                .andExpect(jsonPath("$.active_hosts").value(1))
                .andExpect(jsonPath("$.silent.length()").value(1))
                .andExpect(jsonPath("$.silent[0].entity").value("deadhost.local"))
                .andExpect(jsonPath("$.silent[0].kind").value("went_dark"));
    }

    @Test
    void coverage_roster_surfaces_never_reported_hosts() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "Coverage2");
        String tid = tenantIdByName(platform, "Coverage2");
        seed(tid, "livehost2.local", java.time.Instant.now().minus(java.time.Duration.ofMinutes(10)));

        String admin = "Bearer " + createAdminWithRole(platform, tid, "cov2@coverage.example", "cov-pw-1234", "auditor");
        // set the tenant's expected roster
        mvc.perform(post("/api/v1/coverage/roster").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entities\":[\"ghost.local\",\"livehost2.local\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2));

        // ghost.local is on the roster but never reported; livehost2 has, so it's not flagged
        mvc.perform(get("/api/v1/coverage").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roster_size").value(2))
                .andExpect(jsonPath("$.never_reported.length()").value(1))
                .andExpect(jsonPath("$.never_reported[0].entity").value("ghost.local"));
    }

    @Test
    void coverage_requires_admin_auth() throws Exception {
        mvc.perform(get("/api/v1/coverage")).andExpect(status().isUnauthorized());
    }

    // ---- audit-ready evidence pack (spec 0007) ----

    @Test
    void evidence_requires_auditor_and_is_access_logged() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "Evidence");
        String tid = tenantIdByName(platform, "Evidence");
        ingest(tok, "a normal prompt", "ev-1");
        ingest(tok, "deploy AKIAIOSFODNN7EXAMPLE now", "ev-2");   // one secret → redaction

        // viewer is denied
        String viewer = "Bearer " + createAdminWithRole(platform, tid, "viewer@ev.example", "ev-pw-1234", "viewer");
        mvc.perform(get("/api/v1/evidence").header("Authorization", viewer))
                .andExpect(status().isForbidden());

        // auditor gets a populated bundle
        String auditor = "Bearer " + createAdminWithRole(platform, tid, "auditor@ev.example", "ev-pw-1234", "auditor");
        mvc.perform(get("/api/v1/evidence").header("Authorization", auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant").value(tid))
                .andExpect(jsonPath("$.integrity.ok").exists())
                .andExpect(jsonPath("$.access_log.chain_ok").exists())
                .andExpect(jsonPath("$.coverage.total_hosts").exists())
                .andExpect(jsonPath("$.redaction.records_with_redactions").value(1))
                .andExpect(jsonPath("$.records.total_in_period").value(2))
                .andExpect(jsonPath("$.config.redaction_mode").value("mask"))
                .andExpect(jsonPath("$.bundle_hash").exists());

        // generating evidence was itself recorded in the access log
        mvc.perform(get("/api/v1/access-log").header("Authorization", auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("evidence"));
    }

    @Test
    void evidence_bundle_hash_is_deterministic_and_change_sensitive() throws Exception {
        String platform = "Bearer " + adminToken();
        String tok = createTenant(platform, "EvidenceHash");
        String tid = tenantIdByName(platform, "EvidenceHash");
        ingest(tok, "first", "eh-1");

        java.time.Instant from = java.time.Instant.parse("2020-01-01T00:00:00Z");
        java.time.Instant to = java.time.Instant.parse("2035-01-01T00:00:00Z");
        // EvidenceService.build is a pure function of DB state (bundle_hash excludes generated_at)
        String h1 = (String) evidenceService.build(tid, from, to).get("bundle_hash");
        String h2 = (String) evidenceService.build(tid, from, to).get("bundle_hash");
        org.junit.jupiter.api.Assertions.assertEquals(h1, h2);        // identical state → identical hash

        ingest(tok, "second", "eh-2");
        String h3 = (String) evidenceService.build(tid, from, to).get("bundle_hash");
        org.junit.jupiter.api.Assertions.assertNotEquals(h1, h3);     // data changed → hash changed
    }

    // ---- OIDC SSO endpoints (spec 0008) ----

    @Test
    void oidc_status_is_disabled_by_default() throws Exception {
        mvc.perform(get("/api/v1/auth/oidc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void oidc_login_when_disabled_redirects_with_error() throws Exception {
        mvc.perform(get("/api/v1/auth/oidc/login"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("sso_error")));
    }

    @Test
    void auth_me_requires_session_and_returns_profile() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        String tok = adminToken();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@promptaudit.local"))
                .andExpect(jsonPath("$.role").value("platform"))
                .andExpect(jsonPath("$.cap").value("auditor"));
    }

    // ---- helpers ----

    private int seedSeq = 0;

    /** Persist a synthetic prompt record with a controlled received_at (bypasses ingest for coverage tests). */
    private void seed(String tenant, String host, java.time.Instant when) {
        PromptRecord r = new PromptRecord();
        r.setId("pr_seed_" + (seedSeq++));
        r.setTenantOrgId(tenant);
        r.setHostname(host);
        r.setReceivedAt(when);
        r.setPrompt("x");
        r.setPromptLength(1);
        promptRepo.save(r);
    }

    private String createAdminWithRole(String platformAuth, String tenantId, String email, String pw, String role) throws Exception {
        mvc.perform(post("/api/v1/tenants/" + tenantId + "/admins").header("Authorization", platformAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + pw + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated());
        return login(email, pw);
    }

    private String platformCreatedOrgAdmin(String platformAuth, String tenantId, String email, String pw) throws Exception {
        mvc.perform(post("/api/v1/tenants/" + tenantId + "/admins").header("Authorization", platformAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + pw + "\"}"))
                .andExpect(status().isCreated());
        return login(email, pw);
    }

    private String createTenant(String platformAuth, String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/tenants").header("Authorization", platformAuth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private String tenantIdByName(String platformAuth, String name) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/tenants").header("Authorization", platformAuth))
                .andExpect(status().isOk()).andReturn();
        for (JsonNode t : json.readTree(r.getResponse().getContentAsString()).get("items"))
            if (name.equals(t.get("name").asText())) return t.get("id").asText();
        throw new AssertionError("tenant not found: " + name);
    }

    private void ingest(String ingestToken, String prompt, String session) throws Exception {
        mvc.perform(post("/api/v1/prompts").header("Authorization", "Bearer " + ingestToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"session_id\":\"" + session + "\",\"prompt\":\"" + prompt + "\"}"))
                .andExpect(status().isOk());
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private String adminToken() throws Exception {
        return login("admin@promptaudit.local", "secret-admin");
    }

    @Test
    void bad_admin_login_is_401() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@promptaudit.local\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
