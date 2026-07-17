package com.gigrt.promptaudit.oidc;

import com.gigrt.promptaudit.auth.JwtUtil;
import com.gigrt.promptaudit.auth.SecurityInterceptor;
import com.gigrt.promptaudit.tenant.Tenant;
import com.gigrt.promptaudit.tenant.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OIDC login endpoints (spec 0008). Public (they establish a session). The callback verifies the
 * id_token, maps the identity to a role/tenant (default-deny), mints the app session JWT, and hands it
 * back to the SPA via a same-origin redirect.
 */
@RestController
@RequestMapping("/api/v1/auth/oidc")
public class OidcController {

    private static final long STATE_TTL = 300;   // 5 min for the CSRF state token
    private final SecureRandom rng = new SecureRandom();

    private final OidcProperties props;
    private final OidcService oidc;
    private final JwtUtil jwt;
    private final TenantService tenants;

    public OidcController(OidcProperties props, OidcService oidc, JwtUtil jwt, TenantService tenants) {
        this.props = props; this.oidc = oidc; this.jwt = jwt; this.tenants = tenants;
    }

    /** SPA asks whether to show the "Sign in with SSO" button. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Collections.singletonMap("enabled", props.isUsable());
    }

    /** Platform-admin SSO diagnostics: non-secret config summary + a live discovery probe. Never
     *  returns the client secret. (Config itself is set via env — this page is status + self-test.) */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config(HttpServletRequest req) {
        Map<String, Object> p = SecurityInterceptor.principal(req);
        Object role = p == null ? null : p.get("role");
        if (!(TenantService.ROLE_PLATFORM.equals(role) || "admin".equals(role)))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Collections.singletonMap("error", "forbidden"));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("config", props.describe());
        resp.put("discovery", oidc.testDiscovery());
        return ResponseEntity.ok(resp);
    }

    /** Begin the code flow: 302 to the IdP with a signed state (carrying the nonce). */
    @GetMapping("/login")
    public ResponseEntity<Void> login() {
        if (!props.isUsable()) return redirect("/?sso_error=" + enc("SSO is not configured"));
        String nonce = randomHex();
        Map<String, Object> stateClaims = new LinkedHashMap<>();
        stateClaims.put("typ", "oidc_state");
        stateClaims.put("nonce", nonce);
        String state = jwt.issue(stateClaims, STATE_TTL);
        try {
            return redirect(oidc.authorizeUrl(state, nonce));
        } catch (Exception e) {
            return redirect("/?sso_error=" + enc("SSO unavailable"));
        }
    }

    /** IdP redirect target: verify, map, mint session, hand back to the SPA. */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        if (error != null) return redirect("/?sso_error=" + enc(error));
        Map<String, Object> st = jwt.verify(state);
        if (st == null || !"oidc_state".equals(st.get("typ")))
            return redirect("/?sso_error=" + enc("invalid state"));
        if (code == null || code.isEmpty()) return redirect("/?sso_error=" + enc("missing code"));

        try {
            String idToken = oidc.exchangeCode(code);
            OidcService.Identity id = oidc.verifyIdToken(idToken, str(st.get("nonce")));
            OidcProperties.Mapping m = props.map(id.email);
            if (m == null) return redirect("/?sso_error=" + enc("not authorized"));   // default deny

            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", id.email);
            claims.put("role", m.role);
            claims.put("cap", m.cap);
            if (m.tenant != null) {
                claims.put("tenant", m.tenant);
                Tenant t = tenants.getTenant(m.tenant);
                if (t != null) claims.put("org_name", t.getName());
            }
            String session = jwt.issue(claims);
            return redirect("/?sso=" + enc(session));
        } catch (OidcService.OidcException oe) {
            return redirect("/?sso_error=" + enc(oe.getMessage()));
        } catch (Exception e) {
            return redirect("/?sso_error=" + enc("sign-in failed"));
        }
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private String randomHex() {
        byte[] b = new byte[16];
        rng.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
