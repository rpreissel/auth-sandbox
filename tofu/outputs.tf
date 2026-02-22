output "realm_id" {
  description = "The Keycloak realm ID."
  value       = keycloak_realm.auth_sandbox.id
}

output "device_login_client_id" {
  description = "Client ID of the device-login-client."
  value       = keycloak_openid_client.device_login_client.client_id
}

output "device_login_admin_client_id" {
  description = "Client ID of the device-login-admin client."
  value       = keycloak_openid_client.device_login_admin.client_id
}

output "idp_alias" {
  description = "Alias of the JWT Authorization Grant identity provider."
  value       = keycloak_oidc_identity_provider.device_login_idp.alias
}

output "token_endpoint" {
  description = "OIDC token endpoint of the realm (use as KEYCLOAK_TOKEN_ENDPOINT / KEYCLOAK_ADMIN_TOKEN_ENDPOINT)."
  value       = "https://keycloak.localhost:8443/realms/${keycloak_realm.auth_sandbox.realm}/protocol/openid-connect/token"
}

output "auth_endpoint" {
  description = "OIDC authorization endpoint of the realm (use as KEYCLOAK_AUTH_ENDPOINT)."
  value       = "https://keycloak.localhost:8443/realms/${keycloak_realm.auth_sandbox.realm}/protocol/openid-connect/auth"
}
