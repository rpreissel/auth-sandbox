package dev.authsandbox.devicelogin.service;

import dev.authsandbox.devicelogin.config.JwtProperties;
import dev.authsandbox.devicelogin.config.KeycloakProperties;
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
    private static final String ISSUER = "https://device-login.test";
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
                300L
        );
        KeycloakProperties keycloakProperties = new KeycloakProperties(
                REALM_URL,
                "https://keycloak.test/auth",
                "https://keycloak.test/token",
                "device-login-client",
                "secret",
                "https://device-login.test/callback",
                "openid profile",
                60L
        );
        jwtService = new JwtService(keyPair, jwtProperties, keycloakProperties);
    }

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
                300L
        );
        KeycloakProperties keycloakProperties = new KeycloakProperties(
                REALM_URL,
                "https://keycloak.test/auth",
                "https://keycloak.test/token",
                "device-login-client",
                "secret",
                "https://device-login.test/callback",
                "openid profile",
                60L
        );
        JwtService otherService = new JwtService(otherKeyPair, jwtProperties, keycloakProperties);
        String tokenFromOtherKey = otherService.issueLoginAssertionToken("user-xyz");

        assertThatThrownBy(() -> jwtService.validateToken(tokenFromOtherKey))
                .isInstanceOf(Exception.class);
    }

    @Test
    void toJwkSet_containsKidAndKeyType() {
        Map<String, Object> jwks = jwtService.toJwkSet();

        assertThat(jwks).containsKey("keys");
        @SuppressWarnings("unchecked")
        var keys = (java.util.List<Map<String, Object>>) jwks.get("keys");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).containsEntry("kid", "device-login-key");
        assertThat(keys.get(0)).containsEntry("kty", "RSA");
    }
}
