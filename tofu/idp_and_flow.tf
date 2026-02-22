# ---------------------------------------------------------------------------
# Identity Provider: JWT Authorization Grant
# ---------------------------------------------------------------------------
# Keycloak uses this IdP to validate the device JWT produced by device-login
# and exchange it for an internal Keycloak session.
# The provider type "jwt-auth-grant" corresponds to the built-in
# "JWT Authorization Grant" identity provider that is activated via
# --features=jwt-authorization-grant in compose.yml.
# ---------------------------------------------------------------------------
resource "keycloak_oidc_identity_provider" "device_login_idp" {
  realm             = keycloak_realm.auth_sandbox.id
  alias             = var.device_login_idp_alias
  display_name      = "Device Login JWT IdP"
  provider_id       = "jwt-auth-grant"
  enabled           = true
  store_token       = false
  trust_email       = true
  first_broker_login_flow_alias = "first broker login"

  extra_config = {
    "jwksUrl"       = var.device_login_jwks_url
    "issuer"        = var.jwt_issuer
    "validateSignature" = "true"
    "useJwksUrl"    = "true"
  }
}

# ---------------------------------------------------------------------------
# Authentication Flow: Device Token Flow
# ---------------------------------------------------------------------------
# A custom browser-less flow that uses the DeviceTokenAuthenticator SPI
# execution. This flow is bound to the device-login-idp identity provider
# so that Keycloak invokes the authenticator during the token exchange.
# ---------------------------------------------------------------------------
resource "keycloak_authentication_flow" "device_token_flow" {
  realm_id    = keycloak_realm.auth_sandbox.id
  alias       = "device-token-flow"
  description = "Authentication flow for device JWT token exchange"
  provider_id = "basic-flow"
}

resource "keycloak_authentication_execution" "device_token_authenticator" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = keycloak_authentication_flow.device_token_flow.alias
  authenticator     = "device-token-authenticator"
  requirement       = "REQUIRED"
}
