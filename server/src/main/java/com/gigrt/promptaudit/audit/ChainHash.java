package com.gigrt.promptaudit.audit;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Canonical serialization + hashing for the tamper-evident chain (spec 0001).
 *
 * record_hash = sha256( canonical(record) || prev_hash ).
 *
 * canonical is VERSIONED and LENGTH-PREFIXED ("v1" then, for each field in a frozen order,
 * len(name)‖name‖len(value)‖value), so it's immune to delimiter injection from prompt content
 * and the format can evolve to v2. The field set + order are frozen by the spec — changing them
 * invalidates verification of already-stored rows.
 */
final class ChainHash {

    /** Fixed genesis prev_hash for the first record in a chain. */
    static final String GENESIS = repeat0(64);
    /** Chain key for records with no tenant (reported via the global bootstrap token). */
    static final String GLOBAL_CHAIN = "__global__";

    private ChainHash() {}

    static String chainKey(String tenantOrgId) {
        return (tenantOrgId == null || tenantOrgId.isEmpty()) ? GLOBAL_CHAIN : tenantOrgId;
    }

    /** Recompute this record's hash given the previous hash in its chain. */
    static String hash(PromptRecord r, String prevHash) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(canonical(r));
            sha.update((prevHash == null ? GENESIS : prevHash).getBytes(StandardCharsets.UTF_8));
            return hex(sha.digest());
        } catch (Exception e) {
            throw new RuntimeException("chain hash failed", e);
        }
    }

    private static byte[] canonical(PromptRecord r) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(b);
            d.writeBytes("v1");
            field(d, "id", r.getId());
            field(d, "event_id", r.getEventId());
            field(d, "tenant_org_id", r.getTenantOrgId());
            field(d, "timestamp", r.getTimestamp() == null ? null : r.getTimestamp().toString());
            field(d, "received_at", r.getReceivedAt() == null ? null : r.getReceivedAt().toString());
            field(d, "session_id", r.getSessionId());
            field(d, "user_email", r.getUserEmail());
            field(d, "user_name", r.getUserName());
            field(d, "user_uid", r.getUserUid());
            field(d, "org_id", r.getOrgId());
            field(d, "org_name", r.getOrgName());
            field(d, "repo", r.getRepo());
            field(d, "branch", r.getBranch());
            field(d, "cwd", r.getCwd());
            field(d, "transcript_path", r.getTranscriptPath());
            field(d, "hostname", r.getHostname());
            field(d, "prompt_length", String.valueOf(r.getPromptLength()));
            field(d, "prompt", r.getPrompt());
            d.flush();
            return b.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("canonicalization failed", e);
        }
    }

    private static void field(DataOutputStream d, String name, String value) throws Exception {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        d.writeInt(n.length);
        d.write(n);
        if (value == null) {
            d.writeInt(-1);                 // distinguishes null from empty string
        } else {
            byte[] v = value.getBytes(StandardCharsets.UTF_8);
            d.writeInt(v.length);
            d.write(v);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    private static String repeat0(int n) {
        char[] c = new char[n];
        java.util.Arrays.fill(c, '0');
        return new String(c);
    }
}
