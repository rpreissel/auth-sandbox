package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.config.KeycloakProperties;
import dev.authsandbox.authservice.dto.PushedAuthorizationResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@Service
@Slf4j
@SuppressWarnings({"null", "unchecked"})
public class KeycloakTransferClient {

    private final KeycloakProperties keycloakProperties;
    private final RestClient restClient;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public KeycloakTransferClient(KeycloakProperties keycloakProperties) {
        this.keycloakProperties = keycloakProperties;
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
     * Introspects a Keycloak access token and returns the RFC 7662 claims map.
     * Returns a map with {@code active=false} if the token is invalid or expired.
     */
    public Map<String, Object> introspectToken(String accessToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("token", accessToken);
        body.add("client_id", keycloakProperties.clientId());
        body.add("client_secret", keycloakProperties.clientSecret());

        Map<String, Object> response = restClient.post()
                .uri(keycloakProperties.introspectEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (request, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Token introspection failed: " + resp.getStatusCode());
                })
                .body(Map.class);

        if (response == null) {
            throw new KeycloakUpstreamException("Empty response from Keycloak introspection");
        }
        log.debug("Token introspection completed, active={}", response.get("active"));
        return response;
    }

    /**
     * Pushes an authorization request to Keycloak's PAR endpoint (RFC 9126).
     * Returns the {@code request_uri}, PKCE {@code codeVerifier}, and the signed
     * {@code state} JWT so the caller can redirect the browser and later verify it.
     *
     * @param loginToken signed login_token JWT
     * @param stateJwt   signed state JWT (used as OAuth2 state parameter)
     * @param callbackUri the redirect_uri registered for sso-proxy
     */
    public PushedAuthorizationResponse pushAuthorizationRequest(
            String loginToken, String stateJwt, String callbackUri) {
        String codeVerifier  = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("response_type",         "code");
        body.add("client_id",             keycloakProperties.clientId());
        body.add("client_secret",         keycloakProperties.clientSecret());
        body.add("redirect_uri",          callbackUri);
        body.add("scope",                 keycloakProperties.scope());
        body.add("state",                 stateJwt);
        body.add("code_challenge",        codeChallenge);
        body.add("code_challenge_method", "S256");
        body.add("login_token",           loginToken);

        log.debug("Pushing authorization request to Keycloak PAR endpoint");

        Map<String, Object> parResponse = restClient.post()
                .uri(keycloakProperties.parEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (request, resp) -> {
                    throw new KeycloakUpstreamException(
                            "PAR request failed: " + resp.getStatusCode());
                })
                .body(Map.class);

        if (parResponse == null || !parResponse.containsKey("request_uri")) {
            throw new KeycloakUpstreamException("No request_uri in PAR response");
        }

        String requestUri = (String) parResponse.get("request_uri");
        long expiresIn = parResponse.containsKey("expires_in")
                ? ((Number) parResponse.get("expires_in")).longValue()
                : 60L;

        log.debug("Received PAR request_uri (expires_in={}s)", expiresIn);
        return new PushedAuthorizationResponse(requestUri, codeVerifier, stateJwt, expiresIn);
    }

    /**
     * Builds the Keycloak authorization URL for a PAR-based browser redirect.
     * Uses the public auth endpoint (browser-visible URL) rather than the
     * internal container-to-container endpoint.
     */
    public String buildParAuthUrl(String requestUri) {
        return UriComponentsBuilder.fromUriString(keycloakProperties.authPublicEndpoint())
                .queryParam("client_id",   keycloakProperties.clientId())
                .queryParam("request_uri", requestUri)
                .encode()
                .build()
                .toUriString();
    }

    /**
     * Exchanges an authorization code for OIDC tokens using PKCE.
     * The tokens are intentionally discarded by the caller — we only need
     * the side-effect of Keycloak creating an SSO session cookie in the browser.
     */
    public void exchangeCodeForTokens(String code, String codeVerifier, String callbackUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type",    "authorization_code");
        body.add("client_id",     keycloakProperties.clientId());
        body.add("client_secret", keycloakProperties.clientSecret());
        body.add("code",          code);
        body.add("redirect_uri",  callbackUri);
        body.add("code_verifier", codeVerifier);

        Map<String, Object> response = restClient.post()
                .uri(keycloakProperties.tokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (request, resp) -> {
                    throw new KeycloakUpstreamException(
                            "Token exchange failed: " + resp.getStatusCode());
                })
                .body(Map.class);

        if (response == null) {
            throw new KeycloakUpstreamException("Empty token response from Keycloak");
        }
        log.debug("Code exchange complete — SSO session established in browser");
    }

    // ── PKCE helpers (RFC 7636) ───────────────────────────────────────────

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
