package com.gigrt.promptaudit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

        // 4) admin reads full detail (includes prompt text)
        mvc.perform(get("/api/v1/prompts/" + id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value("refactor the auth module"));

        // 5) admin exports CSV for the current filter
        MvcResult csv = mvc.perform(get("/api/v1/prompts/export?format=csv&keyword=refactor")
                .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("prompts-export.csv")))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertTrue(
                csv.getResponse().getContentAsString().contains("dev@acme.com"));
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
                + "\"hostname\":\"wxks-Mac-mini.local\",\"prompt\":\"hello 审计\"}";

        MvcResult ing = mvc.perform(post("/api/v1/prompts").header("Authorization", INGEST)
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn();
        String id = json.readTree(ing.getResponse().getContentAsString()).get("id").asText();

        String token = adminToken();

        // Detail persists all 5 new columns (not silently dropped).
        mvc.perform(get("/api/v1/prompts/" + id).header("Authorization", "Bearer " + token))
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

    private String adminToken() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@promptaudit.local\",\"password\":\"secret-admin\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void bad_admin_login_is_401() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@promptaudit.local\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
