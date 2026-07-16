package com.gigrt.promptaudit.auth;

import com.gigrt.promptaudit.tenant.TenantService;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * THE security boundary of this service.
 *
 * Two completely separate authentication schemes guard the two audiences, on purpose:
 *
 *   1. INGEST  — POST /api/v1/prompts → a per-org ingest token (or the global bootstrap token).
 *      Write only. Resolving the token also yields the TRUSTED owning tenant, stamped on the record
 *      as {@link #INGEST_TENANT} — so a machine can't forge which org its prompts belong to.
 *
 *   2. ADMIN   — everything else under /api/v1/prompts + /api/v1/tenants + /api/v1/my → a session JWT.
 *      The JWT carries role (platform|org) + tenant; reads are isolated to the caller's tenant.
 *      Platform-only routes (/api/v1/tenants) additionally require role=platform.
 *
 * A leaked ingest token can't read a single record; an admin session can't be minted from one.
 */
public class SecurityInterceptor implements HandlerInterceptor {

    /** Request attribute: the verified admin claims (role/tenant/sub). */
    public static final String PRINCIPAL = "pa.principal";
    /** Request attribute: the trusted owning tenant id for an ingest (null = global/tenantless). */
    public static final String INGEST_TENANT = "pa.ingest.tenant";

    private final JwtUtil jwt;
    private final TenantService tenants;

    public SecurityInterceptor(JwtUtil jwt, TenantService tenants) {
        this.jwt = jwt;
        this.tenants = tenants;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;

        boolean isIngest = "POST".equalsIgnoreCase(req.getMethod())
                && "/api/v1/prompts".equals(req.getRequestURI());
        if (isIngest) return requireIngestToken(req, res);

        if (!requireAdmin(req, res)) return false;
        // Platform-only management routes.
        if (req.getRequestURI().startsWith("/api/v1/tenants") && !isPlatform(req)) return forbid(res);
        return true;
    }

    /** Ingest: resolve the token → owning tenant; stamp it for the controller. Unknown ⇒ fail shut. */
    private boolean requireIngestToken(HttpServletRequest req, HttpServletResponse res) throws Exception {
        String presented = bearer(req);
        if (presented == null) presented = req.getHeader("X-Ingest-Token");
        TenantService.IngestAuth auth = tenants.resolveIngest(presented);
        if (!auth.valid) return deny(res);
        req.setAttribute(INGEST_TENANT, auth.tenantOrgId);   // may be null for the global token
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

    private static boolean isPlatform(HttpServletRequest req) {
        Map<String, Object> p = principal(req);
        if (p == null) return false;
        Object role = p.get("role");
        // platform, plus legacy pre-multi-tenant "admin" sessions (which were the superadmin).
        return TenantService.ROLE_PLATFORM.equals(role) || "admin".equals(role);
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

    private static boolean forbid(HttpServletResponse res) throws Exception {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"forbidden\"}");
        return false;
    }

    /** Convenience for admin handlers. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> principal(HttpServletRequest req) {
        return (Map<String, Object>) req.getAttribute(PRINCIPAL);
    }
}
