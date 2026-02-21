package dev.authsandbox.devicelogin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.keycloak")
public record KeycloakProperties(
        String realmUrl,
        String authEndpoint,
        String tokenEndpoint,
        String clientId,
        String clientSecret,
        String redirectUri,
        String scope,
        long assertionExpirationSeconds
) {}
