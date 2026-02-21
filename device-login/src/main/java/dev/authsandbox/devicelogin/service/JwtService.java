package dev.authsandbox.devicelogin.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import dev.authsandbox.devicelogin.config.JwtProperties;
import dev.authsandbox.devicelogin.config.KeycloakProperties;
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

    private final KeyPair jwtKeyPair;
    private final JwtProperties jwtProperties;
    private final KeycloakProperties keycloakProperties;

    public String issueLoginAssertionToken(String deviceId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(keycloakProperties.assertionExpirationSeconds());

        String token = Jwts.builder()
                .header().add("kid", "device-login-key").and()
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
     * Returns the public key as a JWK Set JSON string, suitable for exposing at a JWKS endpoint.
     * Keycloak's JWT Authorization Grant IdP will fetch this to verify login assertion tokens.
     */
    public Map<String, Object> toJwkSet() {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) jwtKeyPair.getPublic())
                .keyID("device-login-key")
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        return new JWKSet(rsaKey).toJSONObject();
    }
}
