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
import java.util.Optional;

@Service
@Slf4j
@SuppressWarnings("null")
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
     * @param deviceId    the device identifier used as both username and IdP subject
     * @param displayName optional full name split into first/last name in Keycloak;
     *                    pass {@code null} to leave first/last name empty
     * @return the Keycloak user ID (UUID string)
     */
    public String createUserWithFederatedIdentity(String deviceId, String displayName) {
        String adminToken = getAdminToken();
        String userId = createUser(adminToken, deviceId, displayName);
        linkFederatedIdentity(adminToken, userId, deviceId);
        log.info("Created Keycloak user '{}' with federated identity for device '{}'", userId, deviceId);
        return userId;
    }

    /**
     * Convenience overload without a display name.
     */
    public String createUserWithFederatedIdentity(String deviceId) {
        return createUserWithFederatedIdentity(deviceId, null);
    }

    /**
     * Looks up a Keycloak user by exact username match.
     *
     * @param username the username to look up (equals the userId / deviceId)
     * @return the Keycloak user UUID, or {@link Optional#empty()} if not found
     */
    public Optional<String> getUserIdByUsername(String username) {
        String adminToken = getAdminToken();
        String url = adminProperties.baseUrl() + "/admin/realms/" + adminProperties.realm()
                + "/users?username=" + username + "&exact=true";

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = restClient.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Failed to look up Keycloak user by username '" + username + "': " + resp.getStatusCode());
                })
                .body(List.class);

        if (users == null || users.isEmpty()) {
            return Optional.empty();
        }
        String id = (String) users.get(0).get("id");
        return Optional.ofNullable(id);
    }

    /**
     * Deletes a Keycloak user by username, performing an on-demand lookup first.
     * Does nothing (logs a warning) if no user with that username exists.
     *
     * @param username the username of the user to delete
     */
    public void deleteUserByUsername(String username) {
        Optional<String> keycloakUserId = getUserIdByUsername(username);
        if (keycloakUserId.isEmpty()) {
            log.warn("deleteUserByUsername: no Keycloak user found with username '{}' — skipping", username);
            return;
        }
        deleteUser(keycloakUserId.get());
    }

    /**
     * Deletes a Keycloak user by their Keycloak user ID (UUID).
     *
     * @param keycloakUserId the Keycloak-internal user UUID
     */
    public void deleteUser(String keycloakUserId) {
        String adminToken = getAdminToken();
        String userUrl = adminProperties.baseUrl() + "/admin/realms/"
                + adminProperties.realm() + "/users/" + keycloakUserId;

        restClient.delete()
                .uri(userUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Failed to delete Keycloak user '" + keycloakUserId + "': " + resp.getStatusCode());
                })
                .toBodilessEntity();

        log.info("Deleted Keycloak user '{}'", keycloakUserId);
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

    private String createUser(String adminToken, String deviceId, String displayName) {
        String usersUrl = adminProperties.baseUrl() + "/admin/realms/" + adminProperties.realm() + "/users";

        var userRepresentation = new java.util.HashMap<String, Object>();
        userRepresentation.put("username", deviceId);
        userRepresentation.put("enabled", true);
        userRepresentation.put("emailVerified", true);
        userRepresentation.put("requiredActions", List.of());

        if (displayName != null && !displayName.isBlank()) {
            int spaceIdx = displayName.indexOf(' ');
            if (spaceIdx > 0) {
                userRepresentation.put("firstName", displayName.substring(0, spaceIdx).trim());
                userRepresentation.put("lastName", displayName.substring(spaceIdx + 1).trim());
            } else {
                userRepresentation.put("firstName", displayName.trim());
            }
        }

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
