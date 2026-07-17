package com.gigrt.promptaudit;

import com.gigrt.promptaudit.oidc.OidcProperties;
import com.gigrt.promptaudit.oidc.OidcService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the OIDC relying party (spec 0008): the default-deny RBAC mapping, and FULL id_token
 * verification (RS256 signature against a live JWKS + iss/aud/exp/nonce) using a locally-served JWKS +
 * a real RSA-signed token. No Spring context.
 */
class OidcTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void mapping_is_default_deny_with_platform_allowlist_and_rules() {
        OidcProperties p = new OidcProperties(true, "iss", "cid", "sec", "cb", "openid",
                Collections.singletonList("boss@acme.com"),
                Collections.singletonList("dev@acme.com=org_123:auditor"),
                Collections.singletonList("acme.com=org_123:viewer"),
                "deny");

        assertNull(p.map("stranger@other.com"), "no rule ⇒ default deny");
        assertNull(p.map(null));

        OidcProperties.Mapping boss = p.map("Boss@ACME.com");   // case-insensitive
        assertEquals("platform", boss.role);

        OidcProperties.Mapping dev = p.map("dev@acme.com");     // explicit email rule
        assertEquals("org", dev.role);
        assertEquals("auditor", dev.cap);
        assertEquals("org_123", dev.tenant);

        OidcProperties.Mapping dom = p.map("someone@acme.com"); // domain rule → viewer
        assertEquals("org", dom.role);
        assertEquals("viewer", dom.cap);
        assertEquals("org_123", dom.tenant);
    }

    @Test
    void verifies_valid_id_token_and_rejects_tampered_expired_and_wrong_nonce() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("RSA").genKeyPair();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        String jwks = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + B64.encodeToString(pub.getModulus().toByteArray()) + "\",\"e\":\""
                + B64.encodeToString(pub.getPublicExponent().toByteArray()) + "\"}]}";

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        String discovery = "{\"issuer\":\"" + base + "\",\"authorization_endpoint\":\"" + base
                + "/auth\",\"token_endpoint\":\"" + base + "/token\",\"jwks_uri\":\"" + base + "/jwks\"}";
        server.createContext("/.well-known/openid-configuration", ex -> respond(ex, discovery));
        server.createContext("/jwks", ex -> respond(ex, jwks));
        server.start();
        try {
            OidcProperties props = new OidcProperties(true, base, "test-client", "secret", base + "/cb",
                    "openid email", Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), "deny");
            OidcService svc = new OidcService(props);

            long now = System.currentTimeMillis() / 1000;
            String good = idToken(kp, base, "test-client", now + 3600, "nonce-1", "user@acme.com");

            OidcService.Identity id = svc.verifyIdToken(good, "nonce-1");
            assertEquals("user@acme.com", id.email);

            // tampered signature
            String tampered = good.substring(0, good.length() - 4) + "AAAA";
            assertThrows(OidcService.OidcException.class, () -> svc.verifyIdToken(tampered, "nonce-1"));
            // wrong nonce
            assertThrows(OidcService.OidcException.class, () -> svc.verifyIdToken(good, "nonce-2"));
            // expired
            String expired = idToken(kp, base, "test-client", now - 3600, "nonce-1", "user@acme.com");
            assertThrows(OidcService.OidcException.class, () -> svc.verifyIdToken(expired, "nonce-1"));
            // wrong audience
            String wrongAud = idToken(kp, base, "other-client", now + 3600, "nonce-1", "user@acme.com");
            assertThrows(OidcService.OidcException.class, () -> svc.verifyIdToken(wrongAud, "nonce-1"));
        } finally {
            server.stop(0);
        }
    }

    /** Build a real RS256-signed id_token. */
    private static String idToken(KeyPair kp, String iss, String aud, long exp, String nonce, String email) throws Exception {
        String header = B64.encodeToString("{\"alg\":\"RS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payloadJson = "{\"iss\":\"" + iss + "\",\"aud\":\"" + aud + "\",\"exp\":" + exp
                + ",\"nonce\":\"" + nonce + "\",\"email\":\"" + email + "\",\"email_verified\":true,\"name\":\"Test User\"}";
        String payload = B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update((header + "." + payload).getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + B64.encodeToString(s.sign());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
