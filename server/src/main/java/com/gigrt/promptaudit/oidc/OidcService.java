package com.gigrt.promptaudit.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A minimal OIDC relying party (spec 0008) in pure JDK — no Spring Security / OpenSAML. Handles
 * discovery, the authorization-code exchange, and FULL id_token verification (RS256 against the IdP
 * JWKS + iss/aud/exp/nonce). Consistent with the project's hand-rolled JWT/PBKDF2 approach.
 */
@Service
public class OidcService {

    /** Thrown on any verification/protocol failure; the controller turns it into a safe redirect. */
    public static class OidcException extends RuntimeException {
        public OidcException(String m) { super(m); }
    }

    /** Verified identity from the id_token. */
    public static final class Identity {
        public final String email, name;
        Identity(String email, String name) { this.email = email; this.name = name; }
    }

    private static final long LEEWAY = 60;   // seconds of clock skew tolerance

    private final OidcProperties props;
    private final RestTemplate rt = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Base64.Decoder b64url = Base64.getUrlDecoder();

    // caches
    private volatile Map<String, Object> discovery;
    private final Map<String, RSAPublicKey> jwks = new ConcurrentHashMap<>();

    public OidcService(OidcProperties props) { this.props = props; }

    @SuppressWarnings("unchecked")
    private Map<String, Object> discovery() {
        Map<String, Object> d = discovery;
        if (d == null) {
            try {
                d = rt.getForObject(props.issuer + "/.well-known/openid-configuration", Map.class);
            } catch (Exception e) {
                throw new OidcException("OIDC discovery failed: " + e.getMessage());
            }
            if (d == null || d.get("authorization_endpoint") == null || d.get("token_endpoint") == null
                    || d.get("jwks_uri") == null) throw new OidcException("OIDC discovery incomplete");
            discovery = d;
        }
        return d;
    }

    /** Build the IdP authorize URL for the code flow. */
    public String authorizeUrl(String state, String nonce) {
        String authEp = String.valueOf(discovery().get("authorization_endpoint"));
        return authEp + (authEp.contains("?") ? "&" : "?")
                + "response_type=code"
                + "&client_id=" + enc(props.clientId)
                + "&redirect_uri=" + enc(props.redirectUri)
                + "&scope=" + enc(props.scopes)
                + "&state=" + enc(state)
                + "&nonce=" + enc(nonce);
    }

    /** Exchange the authorization code for tokens; returns the raw id_token. */
    @SuppressWarnings("unchecked")
    public String exchangeCode(String code) {
        String tokenEp = String.valueOf(discovery().get("token_endpoint"));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        h.setBasicAuth(props.clientId, props.clientSecret);   // client_secret_basic
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", props.redirectUri);
        Map<String, Object> body;
        try {
            ResponseEntity<Map> resp = rt.exchange(tokenEp, HttpMethod.POST, new HttpEntity<>(form, h), Map.class);
            body = resp.getBody();
        } catch (Exception e) {
            throw new OidcException("token exchange failed: " + e.getMessage());
        }
        if (body == null || body.get("id_token") == null) throw new OidcException("no id_token in token response");
        return String.valueOf(body.get("id_token"));
    }

    /** Verify the id_token: RS256 signature (JWKS) + iss + aud + exp + nonce. */
    @SuppressWarnings("unchecked")
    public Identity verifyIdToken(String idToken, String expectedNonce) {
        String[] parts = idToken == null ? new String[0] : idToken.split("\\.");
        if (parts.length != 3) throw new OidcException("malformed id_token");
        Map<String, Object> header, claims;
        try {
            header = mapper.readValue(b64url.decode(parts[0]), Map.class);
            claims = mapper.readValue(b64url.decode(parts[1]), Map.class);
        } catch (Exception e) {
            throw new OidcException("id_token parse failed");
        }
        if (!"RS256".equals(header.get("alg"))) throw new OidcException("unsupported id_token alg");

        // signature
        String kid = String.valueOf(header.get("kid"));
        RSAPublicKey key = key(kid);
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(b64url.decode(parts[2]))) throw new OidcException("id_token signature invalid");
        } catch (OidcException oe) {
            throw oe;
        } catch (Exception e) {
            throw new OidcException("id_token signature check failed");
        }

        // claims
        if (!String.valueOf(discovery().get("issuer")).equals(claims.get("iss")))
            throw new OidcException("id_token issuer mismatch");
        if (!audMatches(claims.get("aud"))) throw new OidcException("id_token audience mismatch");
        long now = System.currentTimeMillis() / 1000;
        Object exp = claims.get("exp");
        if (!(exp instanceof Number) || ((Number) exp).longValue() + LEEWAY < now)
            throw new OidcException("id_token expired");
        if (expectedNonce != null && !expectedNonce.equals(claims.get("nonce")))
            throw new OidcException("id_token nonce mismatch");
        Object ev = claims.get("email_verified");
        if (ev instanceof Boolean && !((Boolean) ev)) throw new OidcException("email not verified");

        String email = str(claims.get("email"));
        if (email == null || email.isEmpty()) throw new OidcException("id_token has no email");
        String name = str(claims.get("name"));
        return new Identity(email, name != null ? name : email);
    }

    private RSAPublicKey key(String kid) {
        RSAPublicKey k = jwks.get(kid);
        if (k == null) { refreshJwks(); k = jwks.get(kid); }
        if (k == null) throw new OidcException("no JWKS key for kid " + kid);
        return k;
    }

    @SuppressWarnings("unchecked")
    private void refreshJwks() {
        String jwksUri = String.valueOf(discovery().get("jwks_uri"));
        Map<String, Object> doc;
        try {
            doc = rt.getForObject(jwksUri, Map.class);
        } catch (Exception e) {
            throw new OidcException("JWKS fetch failed: " + e.getMessage());
        }
        Object keys = doc == null ? null : doc.get("keys");
        if (!(keys instanceof List)) throw new OidcException("JWKS malformed");
        for (Object o : (List<Object>) keys) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> jwk = (Map<String, Object>) o;
            if (!"RSA".equals(jwk.get("kty")) || jwk.get("n") == null || jwk.get("e") == null) continue;
            try {
                BigInteger n = new BigInteger(1, b64url.decode(str(jwk.get("n"))));
                BigInteger e = new BigInteger(1, b64url.decode(str(jwk.get("e"))));
                RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(n, e));
                jwks.put(str(jwk.get("kid")), pub);
            } catch (Exception ignore) { /* skip a bad key */ }
        }
    }

    private boolean audMatches(Object aud) {
        if (aud == null) return false;
        if (aud instanceof String) return props.clientId.equals(aud);
        if (aud instanceof List) for (Object a : (List<?>) aud) if (props.clientId.equals(a)) return true;
        return false;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
