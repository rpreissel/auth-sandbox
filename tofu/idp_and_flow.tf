# ---------------------------------------------------------------------------
# Identity Provider: JWT Authorization Grant
# ---------------------------------------------------------------------------
# Keycloak uses this IdP to validate the device JWT produced by device-login
# and exchange it for an internal Keycloak session.
# The provider type "jwt-auth-grant" corresponds to the built-in
# "JWT Authorization Grant" identity provider that is activated via
# --features=jwt-authorization-grant in compose.yml.
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# First Broker Login — Review Profile config
# ---------------------------------------------------------------------------
# The built-in "first broker login" flow contains a "Review Profile" step
# (idp-review-profile) that by default triggers VERIFY_PROFILE for users
# with missing profile fields (email, firstName, lastName).
# Device users are identified by userId only and have no email, so we must
# set update.profile.on.first.login = "off" to suppress the prompt.
# ---------------------------------------------------------------------------
data "keycloak_authentication_execution" "review_profile" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "first broker login"
  provider_id       = "idp-review-profile"
}

resource "keycloak_authentication_execution_config" "review_profile_config" {
  realm_id     = keycloak_realm.auth_sandbox.id
  execution_id = data.keycloak_authentication_execution.review_profile.id
  alias        = "review profile config"
  config = {
    "update.profile.on.first.login" = "off"
  }
}

resource "keycloak_oidc_identity_provider" "device_login_idp" {
  realm             = keycloak_realm.auth_sandbox.id
  alias             = var.device_login_idp_alias
  display_name      = "Device Login JWT IdP"
  enabled           = true
  store_token       = false
  trust_email       = true
  first_broker_login_flow_alias = "first broker login"

  authorization_url  = "https://placeholder.invalid/auth"
  token_url          = "https://placeholder.invalid/token"
  client_id          = "placeholder"
  client_secret      = "placeholder"

  jwks_url           = var.device_login_jwks_url
  issuer             = var.jwt_issuer
  validate_signature = true
}

# ---------------------------------------------------------------------------
# Authentication Flow: Login Token Flow
# ---------------------------------------------------------------------------
# A custom flow that uses Conditional Authenticators to handle different
# login token types (device vs sso):
#
# Structure:
#   login-token-flow:
#     - device-login-subflow (CONDITIONAL)
#       - device-login-condition (device-login-condition)
#       - login-token-authenticator (login-token-authenticator)
#     - sso-login-subflow (CONDITIONAL)
#       - sso-login-condition (sso-login-condition)
#       - login-token-authenticator (login-token-authenticator)
#
# The condition authenticators check login_token.type field:
#   - device-login-condition: true if type == "device"
#   - sso-login-condition: true if type == "sso"
# ---------------------------------------------------------------------------
resource "keycloak_authentication_flow" "login_token_flow" {
  realm_id    = keycloak_realm.auth_sandbox.id
  alias       = "login-token-flow"
  description = "Authentication flow for device JWT token exchange"
  provider_id = "basic-flow"
}

# ── Device Login subflow ──────────────────────────────────────────────────
resource "keycloak_authentication_subflow" "device_login_subflow" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = keycloak_authentication_flow.login_token_flow.alias
  alias             = "device-login-subflow"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
}

resource "keycloak_authentication_execution" "device_login_condition" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "device-login-subflow"
  authenticator     = "device-login-condition"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_subflow.device_login_subflow]
}

resource "keycloak_authentication_execution" "device_login_token_authenticator" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "device-login-subflow"
  authenticator     = "login-token-authenticator"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_execution.device_login_condition]
}

resource "keycloak_authentication_execution_config" "device_login_authenticator_config" {
  realm_id     = keycloak_realm.auth_sandbox.id
  execution_id = keycloak_authentication_execution.device_login_token_authenticator.id
  alias        = "device login token authenticator config"
  config = {
    "trusted-client-ids" = var.device_login_client_id
  }
}

# ── SSO Login subflow ─────────────────────────────────────────────────────
resource "keycloak_authentication_subflow" "sso_login_subflow" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = keycloak_authentication_flow.login_token_flow.alias
  alias             = "sso-login-subflow"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
}

resource "keycloak_authentication_execution" "sso_login_condition" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "sso-login-subflow"
  authenticator     = "sso-login-condition"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_subflow.sso_login_subflow]
}

resource "keycloak_authentication_execution" "sso_login_token_authenticator" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "sso-login-subflow"
  authenticator     = "login-token-authenticator"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_execution.sso_login_condition]
}

resource "keycloak_authentication_execution_config" "sso_login_authenticator_config" {
  realm_id     = keycloak_realm.auth_sandbox.id
  execution_id = keycloak_authentication_execution.sso_login_token_authenticator.id
  alias        = "sso login token authenticator config"
  config = {
    "trusted-client-ids" = var.device_login_client_id
  }
}

# ---------------------------------------------------------------------------
# Authentication Flow: Step-Up Browser Flow
# ---------------------------------------------------------------------------
# Standard browser flow extended with Level-of-Authentication conditions:
#   Level 1 — username + password  (ACR "1")
#   Level 2 — additionally OTP     (ACR "2")
# The realm's acr.loa.map attribute maps ACR strings to LoA integers.
#
# Structure (Keycloak actual):
#   step-up-browser-flow:
#     - auth-cookie (ALTERNATIVE)
#     - step-up (subflow)
#       - level1-subflow (CONDITIONAL) → username/password
#       - level2-subflow (CONDITIONAL) → OTP
# ---------------------------------------------------------------------------

resource "keycloak_authentication_flow" "step_up_browser_flow" {
  realm_id    = keycloak_realm.auth_sandbox.id
  alias       = "step-up-browser-flow"
  description = "Browser flow with step-up authentication (LoA 1 = password, LoA 2 = OTP)"
  provider_id = "basic-flow"
}

resource "keycloak_authentication_execution" "step_up_browser_cookie" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = keycloak_authentication_flow.step_up_browser_flow.alias
  authenticator     = "auth-cookie"
  requirement       = "ALTERNATIVE"
}

resource "keycloak_authentication_flow" "step_up_subflow" {
  realm_id    = keycloak_realm.auth_sandbox.id
  alias       = "step-up"
  description = "Step-up subflow for LoA 1 and LoA 2"
  provider_id = "basic-flow"
}

resource "keycloak_authentication_subflow" "step_up_subflow_execution" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = keycloak_authentication_flow.step_up_browser_flow.alias
  alias             = "step-up-auth"
  provider_id       = "basic-flow"
  requirement       = "ALTERNATIVE"
  depends_on        = [keycloak_authentication_execution.step_up_browser_cookie]
}

# ── Level 2 sub-flow: OTP ────────────────────────────────────────────────
resource "keycloak_authentication_subflow" "level2_subflow" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "step-up-auth"
  alias             = "level2-subflow"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 2
  depends_on        = [keycloak_authentication_subflow.step_up_subflow_execution]
}

resource "keycloak_authentication_execution" "level2_condition" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "level2-subflow"
  authenticator     = "conditional-level-of-authentication"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_subflow.level2_subflow]
}

resource "keycloak_authentication_execution_config" "level2_condition_config" {
  realm_id     = keycloak_realm.auth_sandbox.id
  execution_id = keycloak_authentication_execution.level2_condition.id
  alias        = "level2"
  config = {
    "loa-condition-level" = "2"
    "loa-max-age"         = "36000"
  }
}

resource "keycloak_authentication_execution" "level2_otp" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "level2-subflow"
  authenticator     = "auth-otp-form"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_execution_config.level2_condition_config]
}

# ── Level 1 sub-flow: username + password ────────────────────────────────
resource "keycloak_authentication_subflow" "level1_subflow" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "step-up-auth"
  alias             = "level1-subflow"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 1
  depends_on        = [keycloak_authentication_subflow.step_up_subflow_execution]
}

resource "keycloak_authentication_execution" "level1_condition" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "level1-subflow"
  authenticator     = "conditional-level-of-authentication"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_subflow.level1_subflow]
}

resource "keycloak_authentication_execution_config" "level1_condition_config" {
  realm_id     = keycloak_realm.auth_sandbox.id
  execution_id = keycloak_authentication_execution.level1_condition.id
  alias        = "level1"
  config = {
    "loa-condition-level" = "1"
    "loa-max-age"         = "36000"
  }
}

resource "keycloak_authentication_execution" "level1_username_password" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "level1-subflow"
  authenticator     = "auth-username-password-form"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_execution_config.level1_condition_config]
}

# ---------------------------------------------------------------------------
# Flow bindings are set in flow_bindings.tf to avoid circular dependencies
# ---------------------------------------------------------------------------
