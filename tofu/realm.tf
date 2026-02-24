# ---------------------------------------------------------------------------
# Realm
# ---------------------------------------------------------------------------
resource "keycloak_realm" "auth_sandbox" {
  realm   = var.realm_id
  enabled = true

  display_name = "Auth Sandbox"

  # Token lifetimes (sensible defaults for local dev)
  access_token_lifespan        = "5m"
  sso_session_idle_timeout     = "30m"
  sso_session_max_lifespan     = "10h"
  offline_session_idle_timeout = "720h"
}

# ---------------------------------------------------------------------------
# Client: device-login-client  (Standard Flow → OIDC code flow)
# ---------------------------------------------------------------------------
resource "keycloak_openid_client" "device_login_client" {
  realm_id  = keycloak_realm.auth_sandbox.id
  client_id = "device-login-client"
  name      = "Device Login Client"
  enabled   = true

  access_type = "CONFIDENTIAL"

  # Standard Flow only — the device-login service drives the code exchange
  standard_flow_enabled        = true
  implicit_flow_enabled        = false
  direct_access_grants_enabled = false
  service_accounts_enabled     = false

  valid_redirect_uris = [
    var.device_login_redirect_uri,
    var.transfer_callback_uri,
  ]

  client_secret = var.device_login_client_secret

  authentication_flow_binding_overrides {
    browser_id = keycloak_authentication_flow.login_token_flow.id
  }
}

# ---------------------------------------------------------------------------
# Client: device-login-admin  (Client Credentials → Keycloak Admin API)
# ---------------------------------------------------------------------------
resource "keycloak_openid_client" "device_login_admin" {
  realm_id  = keycloak_realm.auth_sandbox.id
  client_id = "device-login-admin"
  name      = "Device Login Admin"
  enabled   = true

  access_type = "CONFIDENTIAL"

  standard_flow_enabled        = false
  implicit_flow_enabled        = false
  direct_access_grants_enabled = false
  service_accounts_enabled     = true   # enables client credentials grant

  client_secret = var.device_login_admin_client_secret
}

# ---------------------------------------------------------------------------
# Service account role assignments for device-login-admin
# The service account needs three realm-management roles:
#   - view-users
#   - manage-users
#   - manage-identity-providers
# ---------------------------------------------------------------------------
data "keycloak_openid_client" "realm_management" {
  realm_id  = keycloak_realm.auth_sandbox.id
  client_id = "realm-management"

  depends_on = [keycloak_realm.auth_sandbox]
}

data "keycloak_role" "view_users" {
  realm_id  = keycloak_realm.auth_sandbox.id
  client_id = data.keycloak_openid_client.realm_management.id
  name      = "view-users"
}

data "keycloak_role" "manage_users" {
  realm_id  = keycloak_realm.auth_sandbox.id
  client_id = data.keycloak_openid_client.realm_management.id
  name      = "manage-users"
}

data "keycloak_role" "manage_identity_providers" {
  realm_id  = keycloak_realm.auth_sandbox.id
  client_id = data.keycloak_openid_client.realm_management.id
  name      = "manage-identity-providers"
}

resource "keycloak_openid_client_service_account_role" "admin_view_users" {
  realm_id                = keycloak_realm.auth_sandbox.id
  service_account_user_id = keycloak_openid_client.device_login_admin.service_account_user_id
  client_id               = data.keycloak_openid_client.realm_management.id
  role                    = data.keycloak_role.view_users.name
}

resource "keycloak_openid_client_service_account_role" "admin_manage_users" {
  realm_id                = keycloak_realm.auth_sandbox.id
  service_account_user_id = keycloak_openid_client.device_login_admin.service_account_user_id
  client_id               = data.keycloak_openid_client.realm_management.id
  role                    = data.keycloak_role.manage_users.name
}

resource "keycloak_openid_client_service_account_role" "admin_manage_idps" {
  realm_id                = keycloak_realm.auth_sandbox.id
  service_account_user_id = keycloak_openid_client.device_login_admin.service_account_user_id
  client_id               = data.keycloak_openid_client.realm_management.id
  role                    = data.keycloak_role.manage_identity_providers.name
}

# ---------------------------------------------------------------------------
# Client: target-app-client  (PUBLIC, PKCE — Auth Code flow from browser SPA)
# ---------------------------------------------------------------------------
resource "keycloak_openid_client" "target_app_client" {
  realm_id  = keycloak_realm.auth_sandbox.id
  client_id = "target-app-client"
  name      = "Target App Client"
  enabled   = true

  access_type = "PUBLIC"

  standard_flow_enabled        = true
  implicit_flow_enabled        = false
  direct_access_grants_enabled = false
  service_accounts_enabled     = false

  # Enforce PKCE S256
  pkce_code_challenge_method = "S256"

  valid_redirect_uris = [
    var.target_app_redirect_uri,
  ]
}
