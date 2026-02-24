package dev.authsandbox.authservice.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import dev.authsandbox.authservice.config.JwtProperties;
import dev.authsandbox.authservice.config.KeycloakProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private static final String KID = "device-login-key";

    private final KeyPair jwtKeyPair;
    private final JwtProperties jwtProperties;
    private final KeycloakProperties keycloakProperties;

    // -----------------------------------------------------------------------
    // Device-login tokens
    // -----------------------------------------------------------------------

    /**
     * Issues a login assertion token for the device auth flow (kid = device-login-key).
     * Keycloak's device-login-idp verifies these tokens.
     */
    public String issueLoginAssertionToken(String deviceId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(keycloakProperties.assertionExpirationSeconds());

        String token = Jwts.builder()
                .header().add("kid", KID).and()
                .id(UUID.randomUUID().toString())
                .subject(deviceId)
                .issuer(jwtProperties.issuer())
                .audience().add(keycloakProperties.realmUrl()).and()
                .claim("azp", keycloakProperties.clientId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(jwtKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        log.debug("Issued login assertion token for device '{}'", deviceId);
        return token;
    }

    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(jwtKeyPair.getPublic())
                .requireIssuer(jwtProperties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Returns the public key as a JWK Set (kid = device-login-key).
     * Served by both JWKS endpoints; Keycloak's device-login-idp verifies all tokens.
     */
    public Map<String, Object> toJwkSet() {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) jwtKeyPair.getPublic())
                .keyID(KID)
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        return new JWKSet(rsaKey).toJSONObject();
    }

    // -----------------------------------------------------------------------
    // SSO transfer tokens
    // -----------------------------------------------------------------------

    /**
     * Issues a login_token JWT for the given userId (kid = device-login-key).
     * Keycloak's device-login-idp verifies these tokens (same IDP as device auth).
     */
    public String issueLoginToken(String userId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(keycloakProperties.assertionExpirationSeconds());

        String token = Jwts.builder()
                .header().add("kid", KID).and()
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .issuer(jwtProperties.issuer())
                .audience().add(keycloakProperties.realmUrl()).and()
                .claim("azp", keycloakProperties.clientId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(jwtKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        log.debug("Issued login_token for userId '{}'", userId);
        return token;
    }

    /**
     * Issues a short-lived Transfer-JWT containing the PAR request_uri and PKCE code_verifier.
     * This token is set as an HttpOnly cookie and used to complete the callback.
     */
    public String issueTransferToken(String requestUri, String codeVerifier) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(jwtProperties.transferTokenTtlSeconds());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("transfer")
                .issuer(jwtProperties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("request_uri", requestUri)
                .claim("code_verifier", codeVerifier)
                .signWith(jwtKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Issues a short-lived State-JWT containing the targetUrl and a nonce.
     * Used as the OAuth2 state parameter — verified in the callback.
     */
    public String issueStateToken(String targetUrl) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(jwtProperties.transferTokenTtlSeconds());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("state")
                .issuer(jwtProperties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("target_url", targetUrl)
                .claim("nonce", UUID.randomUUID().toString())
                .signWith(jwtKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    public Claims validateTransferToken(String token) {
        return Jwts.parser()
                .verifyWith(jwtKeyPair.getPublic())
                .requireIssuer(jwtProperties.issuer())
                .requireSubject("transfer")
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Claims validateStateToken(String token) {
        return Jwts.parser()
                .verifyWith(jwtKeyPair.getPublic())
                .requireIssuer(jwtProperties.issuer())
                .requireSubject("state")
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }


}
