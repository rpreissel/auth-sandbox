package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.config.JwtProperties;
import dev.authsandbox.authservice.config.KeycloakProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;
    private KeyPair keyPair;
    private static final String ISSUER = "https://auth-service.test";
    private static final String REALM_URL = "https://keycloak.test/realms/test";

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();

        JwtProperties jwtProperties = new JwtProperties(
                "classpath:test-keys/private.pem",
                "classpath:test-keys/public.pem",
                ISSUER,
                300L,
                60L
        );
        KeycloakProperties keycloakProperties = new KeycloakProperties(
                REALM_URL,
                "https://keycloak.test/auth",
                "https://keycloak.test/auth-public",
                "https://keycloak.test/token",
                "https://keycloak.test/par",
                "https://keycloak.test/introspect",
                "device-login-client",
                "secret",
                "https://device-login.test/callback",
                "https://sso-proxy.test/callback",
                "openid profile",
                60L
        );
        jwtService = new JwtService(keyPair, jwtProperties, keycloakProperties);
    }

    // -----------------------------------------------------------------------
    // Device-login tokens
    // -----------------------------------------------------------------------

    @Test
    void issueLoginAssertionToken_returnsSignedToken() {
        String token = jwtService.issueLoginAssertionToken("user-123");

        assertThat(token).isNotBlank();
    }

    @Test
    void validateToken_acceptsOwnToken() {
        String token = jwtService.issueLoginAssertionToken("user-abc");

        Claims claims = jwtService.validateToken(token);

        assertThat(claims.getSubject()).isEqualTo("user-abc");
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    }

    @Test
    void validateToken_rejectsTokenIssuedByDifferentKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair otherKeyPair = gen.generateKeyPair();

        JwtProperties jwtProperties = new JwtProperties(
                "classpath:test-keys/private.pem",
                "classpath:test-keys/public.pem",
                ISSUER,
                300L,
                60L
        );
        KeycloakProperties keycloakProperties = new KeycloakProperties(
                REALM_URL,
                "https://keycloak.test/auth",
                "https://keycloak.test/auth-public",
                "https://keycloak.test/token",
                "https://keycloak.test/par",
                "https://keycloak.test/introspect",
                "device-login-client",
                "secret",
                "https://device-login.test/callback",
                "https://sso-proxy.test/callback",
                "openid profile",
                60L
        );
        JwtService otherService = new JwtService(otherKeyPair, jwtProperties, keycloakProperties);
        String tokenFromOtherKey = otherService.issueLoginAssertionToken("user-xyz");

        assertThatThrownBy(() -> jwtService.validateToken(tokenFromOtherKey))
                .isInstanceOf(Exception.class);
    }

    @Test
    void toJwkSet_containsDeviceLoginKid() {
        Map<String, Object> jwks = jwtService.toJwkSet();

        assertThat(jwks).containsKey("keys");
        @SuppressWarnings("unchecked")
        var keys = (java.util.List<Map<String, Object>>) jwks.get("keys");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).containsEntry("kid", "device-login-key");
        assertThat(keys.get(0)).containsEntry("kty", "RSA");
    }

    // -----------------------------------------------------------------------
    // SSO transfer tokens
    // -----------------------------------------------------------------------

    @Test
    void issueLoginToken_returnsSignedToken() {
        String token = jwtService.issueLoginToken("user-456");

        assertThat(token).isNotBlank();
    }

    @Test
    void toJwkSet_containsDeviceLoginKid_forTransferEndpoint() {
        Map<String, Object> jwks = jwtService.toJwkSet();

        assertThat(jwks).containsKey("keys");
        @SuppressWarnings("unchecked")
        var keys = (java.util.List<Map<String, Object>>) jwks.get("keys");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).containsEntry("kid", "device-login-key");
        assertThat(keys.get(0)).containsEntry("kty", "RSA");
    }

    @Test
    void issueAndValidateTransferToken() {
        String token = jwtService.issueTransferToken("urn:ietf:params:oauth:request_uri:abc", "code-verifier-123");

        Claims claims = jwtService.validateTransferToken(token);

        assertThat(claims.getSubject()).isEqualTo("transfer");
        assertThat(claims.get("request_uri", String.class)).isEqualTo("urn:ietf:params:oauth:request_uri:abc");
        assertThat(claims.get("code_verifier", String.class)).isEqualTo("code-verifier-123");
    }

    @Test
    void issueAndValidateStateToken() {
        String token = jwtService.issueStateToken("https://example.com/target");

        Claims claims = jwtService.validateStateToken(token);

        assertThat(claims.getSubject()).isEqualTo("state");
        assertThat(claims.get("target_url", String.class)).isEqualTo("https://example.com/target");
        assertThat(claims.get("nonce", String.class)).isNotBlank();
    }

    @Test
    void validateTransferToken_rejectsStateToken() {
        String stateToken = jwtService.issueStateToken("https://example.com");

        assertThatThrownBy(() -> jwtService.validateTransferToken(stateToken))
                .isInstanceOf(Exception.class);
    }

    @Test
    void validateStateToken_rejectsTransferToken() {
        String transferToken = jwtService.issueTransferToken("urn:request_uri", "verifier");

        assertThatThrownBy(() -> jwtService.validateStateToken(transferToken))
                .isInstanceOf(Exception.class);
    }
}
