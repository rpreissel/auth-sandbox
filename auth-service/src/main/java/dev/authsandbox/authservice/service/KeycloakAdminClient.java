package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.config.KeycloakAdminProperties;
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
     * Creates a Keycloak user for the given userId as the username, then links it
     * to the device-login IdP via a federated identity.
     *
     * @param userId      the user identifier used as both username and IdP subject
     * @param displayName optional full name split into first/last name in Keycloak;
     *                    pass {@code null} to leave first/last name empty
     * @return the Keycloak user ID (UUID string)
     */
    public String createUserWithFederatedIdentity(String userId, String displayName) {
        String adminToken = getAdminToken();
        String keycloakUserId = createUser(adminToken, userId, displayName);
        linkFederatedIdentity(adminToken, keycloakUserId, userId, adminProperties.idpAlias());
        log.info("Created Keycloak user '{}' with federated identity for userId '{}'", keycloakUserId, userId);
        return keycloakUserId;
    }

    /**
     * Looks up a Keycloak user by exact username match.
     *
     * @param username the username to look up (equals the userId)
     * @return the Keycloak user UUID, or {@link Optional#empty()} if not found
     */
    public Optional<String> getUserIdByUsername(String username) {
        String adminToken = getAdminToken();
        String url = usersUrl() + "?username=" + username + "&exact=true";

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

        restClient.delete()
                .uri(userUrl(keycloakUserId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Failed to delete Keycloak user '" + keycloakUserId + "': " + resp.getStatusCode());
                })
                .toBodilessEntity();

        log.info("Deleted Keycloak user '{}'", keycloakUserId);
    }

    /**
     * Ensures the given user has a federated identity link to the device-login IdP.
     * If the link already exists, this is a no-op. If it doesn't exist, the link
     * is created using the username as the IdP subject (matching the device JWT sub).
     *
     * @param username the Keycloak username (equals the userId)
     */
    public void ensureDeviceLoginFederatedIdentityLink(String username) {
        String adminToken = getAdminToken();
        String keycloakUserId = getUserIdByUsername(username)
                .orElseThrow(() -> new KeycloakUpstreamException(
                        "Cannot link device-login-idp: no Keycloak user found for username '" + username + "'"));

        String idpAlias = adminProperties.idpAlias();

        if (hasFederatedIdentityLink(adminToken, keycloakUserId, idpAlias)) {
            log.debug("User '{}' already has '{}' federated identity link", username, idpAlias);
            return;
        }

        linkFederatedIdentity(adminToken, keycloakUserId, username, idpAlias);
        log.info("Created '{}' federated identity link for user '{}'", idpAlias, username);
    }

    /**
     * Ensures the given user has a federated identity link to the sso-proxy-idp.
     * If the link already exists, this is a no-op. If it doesn't exist, the link
     * is created using the username as the IdP subject (matching sso-proxy-idp
     * login_token subject).
     *
     * @param username the Keycloak username (equals the userId)
     */
    public void ensureSsoProxyFederatedIdentityLink(String username) {
        String adminToken = getAdminToken();
        String keycloakUserId = getUserIdByUsername(username)
                .orElseThrow(() -> new KeycloakUpstreamException(
                        "Cannot link sso-proxy-idp: no Keycloak user found for username '" + username + "'"));

        String ssoProxyIdpAlias = adminProperties.ssoProxyIdpAlias();

        // Check if the federated identity link already exists
        if (hasFederatedIdentityLink(adminToken, keycloakUserId, ssoProxyIdpAlias)) {
            log.debug("User '{}' already has '{}' federated identity link", username, ssoProxyIdpAlias);
            return;
        }

        // Create the link
        linkFederatedIdentity(adminToken, keycloakUserId, username, ssoProxyIdpAlias);
        log.info("Created '{}' federated identity link for user '{}'", ssoProxyIdpAlias, username);
    }

    /**
     * Returns true if the given Keycloak user already has a password credential set.
     * Uses the Admin REST API: GET /admin/realms/{realm}/users/{id}/credentials
     * A password credential has type 'password'.
     *
     * @param keycloakUserId the Keycloak-internal user UUID
     * @return true if a password credential exists, false otherwise
     */
    public boolean hasPassword(String keycloakUserId) {
        String adminToken = getAdminToken();
        String url = userUrl(keycloakUserId) + "/credentials";

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> credentials = restClient.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Failed to fetch credentials for user '" + keycloakUserId + "': " + resp.getStatusCode());
                })
                .body(List.class);

        if (credentials == null || credentials.isEmpty()) {
            return false;
        }
        return credentials.stream()
                .anyMatch(c -> "password".equals(c.get("type")));
    }

    /**
     * Sets a password credential for the given Keycloak user.
     * Uses the Admin REST API: PUT /admin/realms/{realm}/users/{id}/reset-password
     *
     * @param keycloakUserId the Keycloak-internal user UUID
     * @param password       the new password to set
     */
    public void setPassword(String keycloakUserId, String password) {
        String adminToken = getAdminToken();
        String url = userUrl(keycloakUserId) + "/reset-password";

        Map<String, Object> credential = Map.of(
                "type", "password",
                "value", password,
                "temporary", false
        );

        restClient.put()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(credential)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Failed to set password for user '" + keycloakUserId + "': " + resp.getStatusCode());
                })
                .toBodilessEntity();

        log.info("Password set for user '{}'", keycloakUserId);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Base URL for the Keycloak users resource in the configured realm. */
    private String usersUrl() {
        return adminProperties.baseUrl() + "/admin/realms/" + adminProperties.realm() + "/users";
    }

    /** URL for a specific Keycloak user by their internal UUID. */
    private String userUrl(String keycloakUserId) {
        return usersUrl() + "/" + keycloakUserId;
    }

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

    /**
     * Creates a Keycloak user with the given userId as username.
     * Idempotent: if the user already exists (Keycloak returns 400 or 409),
     * the existing user's ID is looked up and returned instead of failing.
     *
     * @return the Keycloak user ID (UUID string), whether newly created or pre-existing
     */
    private String createUser(String adminToken, String userId, String displayName) {
        var userRepresentation = new java.util.HashMap<String, Object>();
        userRepresentation.put("username", userId);
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
                .uri(usersUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(userRepresentation)
                .retrieve()
                .onStatus(status -> status.value() == 400 || status.value() == 409, (req, resp) -> {
                    // Keycloak returns 400 (or 409) when the username already exists.
                    // Log and swallow — fall back to lookup below.
                    log.warn("Keycloak user already exists for userId='{}' (HTTP {}); falling back to lookup",
                            userId, resp.getStatusCode().value());
                })
                .onStatus(status -> !status.is2xxSuccessful() && status.value() != 400 && status.value() != 409, (req, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Failed to create Keycloak user '" + userId + "': " + resp.getStatusCode());
                })
                .toBodilessEntity();

        // Always look up by username — works whether user was just created (201)
        // or already existed (400/409 conflict swallowed above).
        // This is simpler and more reliable than parsing the Location header.
        return getUserIdByUsername(userId)
                .orElseThrow(() -> new KeycloakUpstreamException(
                        "Keycloak user not found after creation for userId='" + userId + "'"));
    }

    private void linkFederatedIdentity(String adminToken, String keycloakUserId, String subject, String idpAlias) {
        String federatedIdentityUrl = userUrl(keycloakUserId) + "/federated-identity/" + idpAlias;

        Map<String, String> federatedIdentity = Map.of(
                "identityProvider", idpAlias,
                "userId", subject,
                "userName", subject
        );

        restClient.post()
                .uri(federatedIdentityUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(federatedIdentity)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Failed to link federated identity for IdP '" + idpAlias + "': " + resp.getStatusCode());
                })
                .toBodilessEntity();

        log.debug("Linked federated identity for user '{}' via IdP '{}'", keycloakUserId, idpAlias);
    }

    @SuppressWarnings("unchecked")
    private boolean hasFederatedIdentityLink(String adminToken, String keycloakUserId, String idpAlias) {
        String url = userUrl(keycloakUserId) + "/federated-identity";

        List<Map<String, Object>> links = restClient.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Failed to list federated identities for user '" + keycloakUserId + "': " + resp.getStatusCode());
                })
                .body(List.class);

        if (links == null || links.isEmpty()) {
            return false;
        }
        return links.stream()
                .anyMatch(link -> idpAlias.equals(link.get("identityProvider")));
    }
}
