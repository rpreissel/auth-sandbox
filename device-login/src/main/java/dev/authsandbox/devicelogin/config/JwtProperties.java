package dev.authsandbox.devicelogin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String privateKeyPath,
        String publicKeyPath,
        String issuer,
        long expirationSeconds
) {}
