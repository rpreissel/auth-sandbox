package dev.authsandbox.authservice.dto;

/**
 * Result of a Pushed Authorization Request (PAR) to Keycloak.
 *
 * @param requestUri   the {@code request_uri} returned by Keycloak's PAR endpoint
 * @param codeVerifier the PKCE code_verifier generated for this request
 */
public record PushedAuthorizationResponse(String requestUri, String codeVerifier) {}
