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
    // Keycloak assertion tokens (device-login + SSO transfer flows)
    // -----------------------------------------------------------------------

    /**
     * Issues a signed Keycloak assertion JWT for the given userId (kid = device-login-key).
     * Used by both the device-login and SSO transfer flows; Keycloak's device-login-idp
     * verifies these tokens.
     *
     * @param userId the subject / federated-identity userId
     * @param acr    Authentication Context Class Reference level ("1" = password, "2" = biometric)
     */
    public String issueKeycloakAssertionToken(String userId, String acr) {
        log.debug("Issuing Keycloak assertion token for userId '{}' acr='{}'", userId, acr);
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(keycloakProperties.assertionExpirationSeconds());

        return Jwts.builder()
                .header().add("kid", KID).and()
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .issuer(jwtProperties.issuer())
                .audience().add(keycloakProperties.realmUrl()).and()
                .claim("azp", keycloakProperties.clientId())
                .claim("acr", acr)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(jwtKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
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
     * Issues a short-lived Transfer-JWT containing the PAR {@code request_uri} and an opaque
     * {@code session_id}.
     *
     * <p>The PKCE {@code code_verifier} is intentionally <em>not</em> embedded here; it is
     * stored server-side in {@code transfer_sessions} and looked up by {@code session_id}
     * during the {@code /redeem} step.  This prevents the verifier from being exposed in a
     * URL (browser history, Referer header, access logs).
     *
     * @param requestUri PAR {@code request_uri} returned by Keycloak
     * @param sessionId  opaque identifier linking this JWT to the server-side session row
     */
    public String issueTransferToken(String requestUri, String sessionId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(jwtProperties.transferTokenTtlSeconds());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("transfer")
                .issuer(jwtProperties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("request_uri", requestUri)
                .claim("session_id", sessionId)
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
