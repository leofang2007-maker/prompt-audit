package com.gigrt.promptaudit.access;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Canonical serialization + hashing for the access-log chain (spec 0003), same construction as the
 * prompt chain (spec 0001): record_hash = sha256( canonical(entry) || prev_hash ), with a VERSIONED,
 * LENGTH-PREFIXED canonical form over a frozen field order. Chains are keyed by the accessed tenant so
 * each org's "who looked at our data" log is independently verifiable.
 */
final class AccessChain {

    static final String GENESIS = repeat0(64);
    static final String GLOBAL_CHAIN = "acl:__global__";

    private AccessChain() {}

    /** Chain key for the access log of a given data-tenant (null = tenantless/global). */
    static String chainKey(String tenantOrgId) {
        return (tenantOrgId == null || tenantOrgId.isEmpty()) ? GLOBAL_CHAIN : "acl:" + tenantOrgId;
    }

    static String hash(AdminAccessLog a, String prevHash) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(canonical(a));
            sha.update((prevHash == null ? GENESIS : prevHash).getBytes(StandardCharsets.UTF_8));
            return hex(sha.digest());
        } catch (Exception e) {
            throw new RuntimeException("access chain hash failed", e);
        }
    }

    private static byte[] canonical(AdminAccessLog a) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(b);
            d.writeBytes("a1");
            field(d, "id", a.getId());
            field(d, "actor_email", a.getActorEmail());
            field(d, "actor_role", a.getActorRole());
            field(d, "tenant_org_id", a.getTenantOrgId());
            field(d, "action", a.getAction());
            field(d, "target_record_id", a.getTargetRecordId());
            field(d, "query_json", a.getQueryJson());
            field(d, "reason", a.getReason());
            field(d, "ip", a.getIp());
            field(d, "created_at", a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
            d.flush();
            return b.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("access canonicalization failed", e);
        }
    }

    private static void field(DataOutputStream d, String name, String value) throws Exception {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        d.writeInt(n.length);
        d.write(n);
        if (value == null) {
            d.writeInt(-1);
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
