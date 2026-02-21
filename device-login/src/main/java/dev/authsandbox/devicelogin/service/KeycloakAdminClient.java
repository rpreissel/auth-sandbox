package dev.authsandbox.devicelogin.service;

import dev.authsandbox.devicelogin.config.KeycloakAdminProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class KeycloakAdminClient {

    private final KeycloakAdminProperties adminProperties;
    private final RestClient restClient;

    public KeycloakAdminClient(KeycloakAdminProperties adminProperties) {
        this.adminProperties = adminProperties;
        this.restClient = RestClient.builder()
                .requestInitializer(request ->
                        request.getHeaders().set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .build();
    }

    /**
     * Creates a Keycloak user for the given deviceId as the username, then links it
     * to the device-login IdP via a federated identity.
     *
     * @param deviceId the device identifier used as both username and IdP subject
     * @return the Keycloak user ID (UUID string)
     */
    public String createUserWithFederatedIdentity(String deviceId) {
        String adminToken = getAdminToken();
        String userId = createUser(adminToken, deviceId);
        linkFederatedIdentity(adminToken, userId, deviceId);
        log.info("Created Keycloak user '{}' with federated identity for device '{}'", userId, deviceId);
        return userId;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String getAdminToken() {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", adminProperties.clientId());
        body.add("client_secret", adminProperties.clientSecret());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri(adminProperties.tokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Failed to obtain Keycloak admin token: " + resp.getStatusCode());
                })
                .body(Map.class);

        if (response == null || !response.containsKey("access_token")) {
            throw new KeycloakUpstreamException("No access_token in Keycloak admin token response");
        }
        return (String) response.get("access_token");
    }

    private String createUser(String adminToken, String deviceId) {
        String usersUrl = adminProperties.baseUrl() + "/admin/realms/" + adminProperties.realm() + "/users";

        Map<String, Object> userRepresentation = Map.of(
                "username", deviceId,
                "enabled", true,
                "emailVerified", true,
                "requiredActions", List.of()
        );

        var response = restClient.post()
                .uri(usersUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(userRepresentation)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Failed to create Keycloak user: " + resp.getStatusCode());
                })
                .toBodilessEntity();

        // Keycloak returns 201 Created with Location: .../users/{userId}
        var location = response.getHeaders().getLocation();
        if (location == null) {
            throw new KeycloakUpstreamException("No Location header after Keycloak user creation");
        }
        String path = location.getPath();
        String userId = path.substring(path.lastIndexOf('/') + 1);
        log.debug("Created Keycloak user with id '{}'", userId);
        return userId;
    }

    private void linkFederatedIdentity(String adminToken, String userId, String deviceId) {
        String federatedIdentityUrl = adminProperties.baseUrl() + "/admin/realms/"
                + adminProperties.realm() + "/users/" + userId
                + "/federated-identity/" + adminProperties.idpAlias();

        Map<String, String> federatedIdentity = Map.of(
                "identityProvider", adminProperties.idpAlias(),
                "userId", deviceId,
                "userName", deviceId
        );

        restClient.post()
                .uri(federatedIdentityUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(federatedIdentity)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Failed to link federated identity: " + resp.getStatusCode());
                })
                .toBodilessEntity();

        log.debug("Linked federated identity for user '{}' via IdP '{}'", userId, adminProperties.idpAlias());
    }
}
