package dev.authsandbox.devicelogin.dto;

/**
 * Result of a bulk Keycloak user sync operation over all registration codes.
 *
 * @param synced         number of codes for which a Keycloak user was created successfully
 * @param alreadySynced  number of codes that already had a Keycloak user — skipped
 * @param failed         number of codes for which Keycloak user creation failed
 */
public record SyncResult(int synced, int alreadySynced, int failed) {}
