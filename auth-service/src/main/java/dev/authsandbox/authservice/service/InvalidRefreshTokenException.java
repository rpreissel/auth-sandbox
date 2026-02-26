package dev.authsandbox.authservice.service;

/**
 * Thrown when Keycloak rejects a refresh token (e.g. session was logged out or token expired).
 * Maps to HTTP 401 Unauthorized so the client knows to re-authenticate.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
