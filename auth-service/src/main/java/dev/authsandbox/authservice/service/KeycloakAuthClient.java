package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.config.KeycloakProperties;
import dev.authsandbox.authservice.dto.KeycloakTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
@Slf4j
@SuppressWarnings("null")
public class KeycloakAuthClient {

    private final KeycloakProperties keycloakProperties;
    private final RestClient restClient;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public KeycloakAuthClient(KeycloakProperties keycloakProperties) {
        this.keycloakProperties = keycloakProperties;
        // Use Apache HttpClient 5 configured to NOT follow redirects.
        // We need to capture the 302 Location header (contains code + state) ourselves.
        HttpClient httpClient = HttpClients.custom()
                .disableRedirectHandling()
                .disableCookieManagement()
                .build();
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .requestInitializer(request ->
                        request.getHeaders().set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .build();
    }

    /**
     * Executes the Authorization Code Flow with PKCE against Keycloak.
     *
     * <p>Steps:
     * <ol>
     *   <li>Build auth URL with {@code login_token}, PKCE, and {@code state} parameters</li>
     *   <li>Send GET to auth endpoint; expect 302 with {@code code} in {@code Location} header</li>
     *   <li>Exchange code + code_verifier for OIDC tokens</li>
     * </ol>
     *
     * @param loginToken the signed login assertion JWT
     * @return full OIDC token response from Keycloak
     */
    public KeycloakTokenResponse authenticate(String loginToken) {
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String state = UUID.randomUUID().toString();

        String code = authorize(loginToken, codeChallenge, state);
        return exchangeCodeForTokens(code, codeVerifier, keycloakProperties.redirectUri());
    }

    /**
     * Exchanges an authorization code for OIDC tokens using a specific redirect URI.
     *
     * @param code         the authorization code received from Keycloak
     * @param codeVerifier the PKCE code verifier generated during the authorization request
     * @param redirectUri  the redirect URI used in the original authorization request
     * @return full OIDC token response from Keycloak
     */
    public KeycloakTokenResponse exchangeCodeForTokens(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", keycloakProperties.clientId());
        body.add("client_secret", keycloakProperties.clientSecret());
        body.add("code", code);
        body.add("redirect_uri", redirectUri);
        body.add("code_verifier", codeVerifier);

        KeycloakTokenResponse tokens = restClient.post()
                .uri(keycloakProperties.tokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (request, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Token exchange failed with status: " + resp.getStatusCode());
                })
                .body(KeycloakTokenResponse.class);

        if (tokens == null) {
            throw new KeycloakUpstreamException("Empty token response from Keycloak");
        }

        log.debug("Successfully exchanged authorization code for OIDC tokens");
        return tokens;
    }

    /**
     * Exchanges a Keycloak refresh token for a new set of OIDC tokens.
     *
     * @param refreshToken the Keycloak refresh token issued in a previous auth flow
     * @return new OIDC token response from Keycloak
     */
    public KeycloakTokenResponse refreshToken(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("client_id", keycloakProperties.clientId());
        body.add("client_secret", keycloakProperties.clientSecret());
        body.add("refresh_token", refreshToken);

        KeycloakTokenResponse tokens = restClient.post()
                .uri(keycloakProperties.tokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError(), (request, resp) -> {
                    throw new InvalidRefreshTokenException(
                            "Refresh token rejected by Keycloak with status: " + resp.getStatusCode());
                })
                .onStatus(status -> !status.is2xxSuccessful(), (request, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Token refresh failed with status: " + resp.getStatusCode());
                })
                .body(KeycloakTokenResponse.class);

        if (tokens == null) {
            throw new KeycloakUpstreamException("Empty token response from Keycloak during refresh");
        }

        log.debug("Successfully refreshed OIDC tokens");
        return tokens;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String authorize(String loginToken, String codeChallenge, String state) {
        URI authUri = UriComponentsBuilder.fromUriString(keycloakProperties.authEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", keycloakProperties.clientId())
                .queryParam("redirect_uri", keycloakProperties.redirectUri())
                .queryParam("scope", keycloakProperties.scope())
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("login_token", loginToken)
                .encode()
                .build()
                .toUri();

        log.debug("Sending authorization request to Keycloak");

        var response = restClient.get()
                .uri(authUri)
                .retrieve()
                .toBodilessEntity();

        // Keycloak should redirect with 302; the redirect URL contains ?code=...&state=...
        if (response.getStatusCode() != HttpStatus.FOUND) {
            throw new KeycloakUpstreamException(
                    "Expected 302 from Keycloak auth endpoint, got: " + response.getStatusCode());
        }

        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new KeycloakUpstreamException("No Location header in Keycloak auth response");
        }

        String returnedState = UriComponentsBuilder.fromUri(location)
                .build().getQueryParams().getFirst("state");
        if (!state.equals(returnedState)) {
            throw new KeycloakUpstreamException("State mismatch in Keycloak auth response");
        }

        String code = UriComponentsBuilder.fromUri(location)
                .build().getQueryParams().getFirst("code");
        if (code == null || code.isBlank()) {
            String error = UriComponentsBuilder.fromUri(location)
                    .build().getQueryParams().getFirst("error");
            throw new KeycloakUpstreamException(
                    "No authorization code in Keycloak redirect. error=" + error);
        }

        log.debug("Received authorization code from Keycloak");
        return code;
    }

    // -----------------------------------------------------------------------
    // PKCE helpers (RFC 7636)
    // -----------------------------------------------------------------------

    public static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
