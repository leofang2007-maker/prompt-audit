package com.gigrt.promptaudit.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin login → session JWT. Credentials come from configuration/env (ADMIN_EMAIL / ADMIN_PASSWORD),
 * NOT the database — this is the single-admin demo posture (no user table, no RBAC, no SSO).
 *
 * This login is entirely independent from the ingest token (see {@link SecurityInterceptor}).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Value("${app.admin.email:admin@promptaudit.local}")
    private String adminEmail;

    @Value("${app.admin.password:changeme}")
    private String adminPassword;

    private final JwtUtil jwt;

    public AuthController(JwtUtil jwt) { this.jwt = jwt; }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body) {
        String email = body != null ? str(body.get("email")) : null;
        String password = body != null ? str(body.get("password")) : null;

        boolean ok = email != null
                && constantEquals(email, adminEmail)
                && password != null
                && constantEquals(password, adminPassword);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Collections.singletonMap("error", "invalid_credentials"));
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", adminEmail);
        claims.put("role", "admin");
        String token = jwt.issue(claims);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("email", adminEmail);
        profile.put("role", "admin");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("profile", profile);
        return ResponseEntity.ok(resp);
    }

    /**
     * Stateless logout. The JWT is self-contained (no server session), so logout is a client-side
     * token drop; the server just acknowledges. Kept as an endpoint so the contract is explicit.
     */
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        return Collections.singletonMap("ok", true);
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static boolean constantEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
        return r == 0;
    }
}
