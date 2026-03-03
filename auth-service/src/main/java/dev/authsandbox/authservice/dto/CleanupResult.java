package dev.authsandbox.authservice.dto;

/**
 * Result of cleaning up expired registration codes.
 *
 * @param deleted    number of expired codes that were deleted (with Keycloak user)
 * @param skipped    number of expired codes that were skipped (have registered devices)
 */
public record CleanupResult(int deleted, int skipped) {}
