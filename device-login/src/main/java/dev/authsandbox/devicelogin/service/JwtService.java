package dev.authsandbox.devicelogin.service;

import dev.authsandbox.devicelogin.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private final KeyPair jwtKeyPair;
    private final JwtProperties jwtProperties;

    public String issueDeviceToken(String deviceId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(jwtProperties.expirationSeconds());

        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(deviceId)
                .issuer(jwtProperties.issuer())
                .audience().add("keycloak").and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("device_id", deviceId)
                .claim("token_type", "device_token")
                .signWith(jwtKeyPair.getPrivate())
                .compact();

        log.debug("Issued device token for device '{}'", deviceId);
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
}
