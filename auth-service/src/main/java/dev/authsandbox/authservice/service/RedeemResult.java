package dev.authsandbox.authservice.service;

/**
 * Holds the result of a transfer token redeem operation.
 *
 * @param authUrl      the Keycloak PAR authorization URL to redirect the browser to
 * @param codeVerifier the PKCE code_verifier to store in an HttpOnly cookie
 */
public record RedeemResult(String authUrl, String codeVerifier) {}
