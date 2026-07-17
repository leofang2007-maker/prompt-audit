package com.gigrt.promptaudit.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HS256 JWT (no external dependency, Java 8). Signs/validates the admin session token.
 * Claims: sub(email), role, iat, exp.
 */
@Component
public class JwtUtil {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder b64d = Base64.getUrlDecoder();

    @Value("${app.jwt.secret:change-me-dev-secret}")
    private String secret;

    @Value("${app.jwt.ttlSeconds:43200}") // 12h
    private long ttlSeconds;

    public String issue(Map<String, Object> claims) {
        return issue(claims, ttlSeconds);
    }

    /** Issue with an explicit TTL (used e.g. for the short-lived OIDC {@code state} token). */
    public String issue(Map<String, Object> claims, long ttl) {
        try {
            long now = System.currentTimeMillis() / 1000;
            Map<String, Object> payload = new LinkedHashMap<>(claims);
            payload.put("iat", now);
            payload.put("exp", now + ttl);
            String h = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String p = b64.encodeToString(mapper.writeValueAsBytes(payload));
            String sig = sign(h + "." + p);
            return h + "." + p + "." + sig;
        } catch (Exception e) {
            throw new RuntimeException("jwt issue failed", e);
        }
    }

    /** Returns claims if valid + unexpired, else null. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verify(String token) {
        try {
            if (token == null) return null;
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            if (!constantEquals(sign(parts[0] + "." + parts[1]), parts[2])) return null;
            Map<String, Object> claims = mapper.readValue(b64d.decode(parts[1]), Map.class);
            Object exp = claims.get("exp");
            long e = exp instanceof Number ? ((Number) exp).longValue() : 0;
            if (e < System.currentTimeMillis() / 1000) return null;
            return claims;
        } catch (Exception ex) {
            return null;
        }
    }

    private String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return b64.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
