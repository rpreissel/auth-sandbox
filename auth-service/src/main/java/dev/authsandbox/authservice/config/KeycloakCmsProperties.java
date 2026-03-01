package dev.authsandbox.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.keycloak.cms")
public record KeycloakCmsProperties(
        String clientId,
        String clientSecret,
        String callbackUri,
        String userInfoEndpoint,
        String authPublicEndpoint,
        String tokenEndpoint
) {}
