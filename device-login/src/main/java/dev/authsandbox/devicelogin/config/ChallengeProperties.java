package dev.authsandbox.devicelogin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.challenge")
public record ChallengeProperties(
        long expirationSeconds
) {}
