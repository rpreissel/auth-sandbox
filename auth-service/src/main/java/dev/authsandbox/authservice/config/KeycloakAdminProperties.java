package dev.authsandbox.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.keycloak.admin")
public record KeycloakAdminProperties(
        String clientId,
        String clientSecret,
        String realm,
        String idpAlias,
        String ssoProxyIdpAlias,
        String tokenEndpoint,
        String baseUrl
) {}
