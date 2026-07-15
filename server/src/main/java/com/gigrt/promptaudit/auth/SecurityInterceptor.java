package com.gigrt.promptaudit.auth;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * THE security boundary of this service — and the demo's headline point.
 *
 * Two completely separate authentication schemes guard the two audiences, on purpose:
 *
 *   1. INGEST  — POST /api/v1/prompts        → a shared bearer token (INGEST_TOKEN).
 *      Handed to every developer machine's client hook. It can ONLY write.
 *
 *   2. ADMIN   — GET  /api/v1/prompts[...]    → an admin session JWT (login required).
 *      Only the compliance/security team can read/search/export the audit log.
 *
 * They never overlap: a leaked ingest token cannot read back a single audit record,
 * and an admin session cannot be minted from an ingest token. Keeping this decision in
 * ONE readable place is deliberate — it is the property the whole product sells.
 */
public class SecurityInterceptor implements HandlerInterceptor {

    /** Request attribute holding the verified admin claims (for read/export handlers). */
    public static final String PRINCIPAL = "pa.principal";

    private final JwtUtil jwt;
    private final String ingestToken;

    public SecurityInterceptor(JwtUtil jwt, String ingestToken) {
        this.jwt = jwt;
        this.ingestToken = ingestToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;

        // The ONLY ingest route is `POST /api/v1/prompts`; everything else under /prompts is admin-read.
        boolean isIngest = "POST".equalsIgnoreCase(req.getMethod())
                && "/api/v1/prompts".equals(req.getRequestURI());

        return isIngest ? requireIngestToken(req, res) : requireAdmin(req, res);
    }

    /** Ingest: constant-time compare against INGEST_TOKEN (Authorization: Bearer, or X-Ingest-Token). */
    private boolean requireIngestToken(HttpServletRequest req, HttpServletResponse res) throws Exception {
        String presented = bearer(req);
        if (presented == null) presented = req.getHeader("X-Ingest-Token");
        // Empty configured token ⇒ endpoint is closed (fail shut), never open.
        if (ingestToken == null || ingestToken.isEmpty() || presented == null
                || !constantEquals(presented, ingestToken)) {
            return deny(res);
        }
        return true;
    }

    /** Admin: verify the session JWT (Authorization: Bearer, or ?token= for downloads/links). */
    private boolean requireAdmin(HttpServletRequest req, HttpServletResponse res) throws Exception {
        String token = bearer(req);
        if (token == null) token = req.getParameter("token");
        Map<String, Object> claims = jwt.verify(token);
        if (claims == null) return deny(res);
        req.setAttribute(PRINCIPAL, claims);
        return true;
    }

    private static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7) : null;
    }

    private static boolean deny(HttpServletResponse res) throws Exception {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"unauthorized\"}");
        return false;
    }

    private static boolean constantEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
        return r == 0;
    }

    /** Convenience for admin handlers. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> principal(HttpServletRequest req) {
        return (Map<String, Object>) req.getAttribute(PRINCIPAL);
    }
}
