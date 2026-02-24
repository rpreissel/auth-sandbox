package dev.authsandbox.authservice.service;

/**
 * Thrown when an upstream call to Keycloak fails unexpectedly.
 * Maps to HTTP 502 Bad Gateway in the global exception handler.
 */
public class KeycloakUpstreamException extends RuntimeException {

    public KeycloakUpstreamException(String message) {
        super(message);
    }

    public KeycloakUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
