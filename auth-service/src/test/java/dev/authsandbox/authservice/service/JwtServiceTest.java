package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
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

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();

        JwtProperties jwtProperties = new JwtProperties(
                "classpath:test-keys/private.pem",
                "classpath:test-keys/public.pem",
                ISSUER,
                60L
        );
        jwtService = new JwtService(keyPair, jwtProperties);
    }

    // -----------------------------------------------------------------------
    // JWK Set
    // -----------------------------------------------------------------------

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
    void issueAndValidateTransferToken() {
        String sessionId = "550e8400-e29b-41d4-a716-446655440000";
        String token = jwtService.issueTransferToken("urn:ietf:params:oauth:request_uri:abc", sessionId);

        Claims claims = jwtService.validateTransferToken(token);

        assertThat(claims.getSubject()).isEqualTo("transfer");
        assertThat(claims.get("request_uri", String.class)).isEqualTo("urn:ietf:params:oauth:request_uri:abc");
        assertThat(claims.get("session_id", String.class)).isEqualTo(sessionId);
        assertThat(claims.get("code_verifier", String.class)).isNull();
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
