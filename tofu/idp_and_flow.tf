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
  enabled           = true
  store_token       = false
  trust_email       = true
  first_broker_login_flow_alias = "first broker login"

  # The mrparkers provider requires these OIDC fields even though jwt-auth-grant
  # does not use them. Keycloak ignores them for this provider type.
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
# A custom browser-less flow that uses the LoginTokenAuthenticator SPI
# execution. This flow is bound to the device-login-idp identity provider
# so that Keycloak invokes the authenticator during the token exchange.
# ---------------------------------------------------------------------------
resource "keycloak_authentication_flow" "login_token_flow" {
  realm_id    = keycloak_realm.auth_sandbox.id
  alias       = "login-token-flow"
  description = "Authentication flow for device JWT token exchange"
  provider_id = "basic-flow"
}

resource "keycloak_authentication_execution" "login_token_authenticator" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = keycloak_authentication_flow.login_token_flow.alias
  authenticator     = "login-token-authenticator"
  requirement       = "REQUIRED"
}

# ---------------------------------------------------------------------------
# Authentication Flow: Step-Up Browser Flow
# ---------------------------------------------------------------------------
# Standard browser flow extended with Level-of-Authentication conditions:
#   Level 1 — username + password  (ACR "1")
#   Level 2 — additionally OTP     (ACR "2")
# The realm's acr.loa.map attribute maps ACR strings to LoA integers.
# ---------------------------------------------------------------------------

resource "keycloak_authentication_flow" "step_up_browser_flow" {
  realm_id    = keycloak_realm.auth_sandbox.id
  alias       = "step-up-browser-flow"
  description = "Browser flow with step-up authentication (LoA 1 = password, LoA 2 = OTP)"
  provider_id = "basic-flow"
}

# ── Cookie sub-flow: SSO re-use ──────────────────────────────────────────
# auth-cookie cannot be placed as ALTERNATIVE at the same level as CONDITIONAL
# sub-flows: Keycloak's DefaultAuthenticationFlow.fillListsOfExecutions() treats
# CONDITIONAL the same as REQUIRED when detecting the mixed-mode warning and
# clears the alternative list entirely (auth-cookie would be ignored).
# Solution: wrap auth-cookie in its own CONDITIONAL sub-flow with a LoA=0
# condition so it runs before the level1/level2 sub-flows.
resource "keycloak_authentication_subflow" "cookie_subflow" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = keycloak_authentication_flow.step_up_browser_flow.alias
  alias             = "cookie-subflow"
  provider_id       = "basic-flow"
  description       = "SSO cookie check (conditional)"
  requirement       = "CONDITIONAL"
}

resource "keycloak_authentication_execution" "cookie_loa_condition" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "cookie-subflow"
  authenticator     = "conditional-level-of-authentication"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_subflow.cookie_subflow]
}

resource "keycloak_authentication_execution_config" "cookie_loa_condition_config" {
  realm_id     = keycloak_realm.auth_sandbox.id
  execution_id = keycloak_authentication_execution.cookie_loa_condition.id
  alias        = "cookie-loa-condition-config"
  config = {
    "loa-condition-level" = "0"
    "loa-max-age"         = "36000"
  }
}

resource "keycloak_authentication_execution" "step_up_cookie" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = "cookie-subflow"
  authenticator     = "auth-cookie"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_execution_config.cookie_loa_condition_config]
}

# ── Level 1 sub-flow: username + password ────────────────────────────────
resource "keycloak_authentication_subflow" "level1_subflow" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = keycloak_authentication_flow.step_up_browser_flow.alias
  alias             = "level1-subflow"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  depends_on        = [keycloak_authentication_execution.step_up_cookie]
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
  alias        = "level1-condition-config"
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

# ── Level 2 sub-flow: OTP ────────────────────────────────────────────────
resource "keycloak_authentication_subflow" "level2_subflow" {
  realm_id          = keycloak_realm.auth_sandbox.id
  parent_flow_alias = keycloak_authentication_flow.step_up_browser_flow.alias
  alias             = "level2-subflow"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  depends_on        = [keycloak_authentication_execution.level1_username_password]
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
  alias        = "level2-condition-config"
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

