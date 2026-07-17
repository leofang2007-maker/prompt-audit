package com.gigrt.promptaudit.oidc;

import com.gigrt.promptaudit.tenant.TenantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OIDC configuration + identity→role mapping (spec 0008). Mapping is DEFAULT-DENY: a successfully
 * authenticated IdP user with no matching rule gets no access. Platform role is granted ONLY via the
 * explicit {@code platform-emails} allowlist — never a domain rule.
 */
@Component
public class OidcProperties {

    public final boolean enabled;
    public final String issuer, clientId, clientSecret, redirectUri, scopes, defaultRule;
    private final List<String> platformEmails;
    private final Map<String, String> emailRoles = new LinkedHashMap<>();   // email → "tenant:cap"
    private final Map<String, String> domainRoles = new LinkedHashMap<>();  // domain → "tenant:cap"

    public OidcProperties(
            @Value("${app.oidc.enabled:false}") boolean enabled,
            @Value("${app.oidc.issuer:}") String issuer,
            @Value("${app.oidc.client-id:}") String clientId,
            @Value("${app.oidc.client-secret:}") String clientSecret,
            @Value("${app.oidc.redirect-uri:}") String redirectUri,
            @Value("${app.oidc.scopes:openid email profile}") String scopes,
            @Value("${app.oidc.platform-emails:}") List<String> platformEmails,
            @Value("${app.oidc.email-roles:}") List<String> emailRoles,
            @Value("${app.oidc.domain-roles:}") List<String> domainRoles,
            @Value("${app.oidc.default:deny}") String defaultRule) {
        this.enabled = enabled;
        this.issuer = trim(issuer);
        this.clientId = trim(clientId);
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.redirectUri = trim(redirectUri);
        this.scopes = scopes;
        this.defaultRule = trim(defaultRule);
        this.platformEmails = lower(platformEmails);
        parsePairs(emailRoles, this.emailRoles);
        parsePairs(domainRoles, this.domainRoles);
    }

    /** Fully configured + turned on. */
    public boolean isUsable() {
        return enabled && !issuer.isEmpty() && !clientId.isEmpty()
                && !clientSecret.isEmpty() && !redirectUri.isEmpty();
    }

    /** Resolved mapping for an authenticated identity, or null = deny. */
    public static final class Mapping {
        public final String role, cap, tenant;   // role: platform|org; cap: viewer|auditor; tenant: id|null
        Mapping(String role, String cap, String tenant) { this.role = role; this.cap = cap; this.tenant = tenant; }
    }

    public Mapping map(String email) {
        if (email == null || email.isEmpty()) return null;
        String e = email.toLowerCase();
        if (platformEmails.contains(e)) return new Mapping(TenantService.ROLE_PLATFORM, TenantService.CAP_AUDITOR, null);
        if (emailRoles.containsKey(e)) return parseRule(emailRoles.get(e));
        int at = e.indexOf('@');
        String domain = at >= 0 ? e.substring(at + 1) : "";
        if (!domain.isEmpty() && domainRoles.containsKey(domain)) return parseRule(domainRoles.get(domain));
        if (!"deny".equalsIgnoreCase(defaultRule) && !defaultRule.isEmpty()) return parseRule(defaultRule);
        return null;   // default deny
    }

    /** "tenantId:cap" → an org mapping. Unknown cap ⇒ viewer (least privilege). */
    private static Mapping parseRule(String rule) {
        if (rule == null) return null;
        int i = rule.lastIndexOf(':');
        if (i <= 0) return null;
        String tenant = rule.substring(0, i).trim();
        String cap = rule.substring(i + 1).trim();
        if (!TenantService.CAP_AUDITOR.equals(cap)) cap = TenantService.CAP_VIEWER;
        return new Mapping(TenantService.ROLE_ORG, cap, tenant);
    }

    private static void parsePairs(List<String> entries, Map<String, String> out) {
        if (entries == null) return;
        for (String s : entries) {
            if (s == null) continue;
            int i = s.indexOf('=');
            if (i > 0 && i < s.length() - 1) out.put(s.substring(0, i).trim().toLowerCase(), s.substring(i + 1).trim());
        }
    }

    private static List<String> lower(List<String> in) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (in != null) for (String s : in) if (s != null && !s.trim().isEmpty()) out.add(s.trim().toLowerCase());
        return out;
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
