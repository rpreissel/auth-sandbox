package dev.authsandbox.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.keycloak")
public record KeycloakProperties(
        String realmUrl,
        String authEndpoint,
        String authPublicEndpoint,
        String tokenEndpoint,
        String parEndpoint,
        String introspectEndpoint,
        String clientId,
        String clientSecret,
        String redirectUri,
        String callbackUri,
        String scope,
        long assertionExpirationSeconds
) {}
