variable "keycloak_url" {
  description = "Base URL of the Keycloak instance."
  type        = string
  default     = "https://keycloak.localhost:8443"
}

variable "keycloak_admin_username" {
  description = "Keycloak admin username (KEYCLOAK_ADMIN)."
  type        = string
  default     = "admin"
}

variable "keycloak_admin_password" {
  description = "Keycloak admin password (KEYCLOAK_ADMIN_PASSWORD)."
  type        = string
  sensitive   = true
}

variable "realm_id" {
  description = "Name / ID of the realm to create."
  type        = string
  default     = "auth-sandbox"
}

variable "device_login_client_secret" {
  description = "Client secret for device-login-client (KEYCLOAK_CLIENT_SECRET)."
  type        = string
  sensitive   = true
}

variable "device_login_redirect_uri" {
  description = "Valid redirect URI for device-login-client (KEYCLOAK_REDIRECT_URI)."
  type        = string
  default     = "https://auth-service.localhost:8443/api/v1/auth/callback"
}

variable "transfer_callback_uri" {
  description = "Redirect URI for the SSO transfer callback (KEYCLOAK_CALLBACK_URI)."
  type        = string
  default     = "https://auth-service.localhost:8443/api/v1/transfer/callback"
}

variable "target_app_redirect_uri" {
  description = "Redirect URI for target-app-client (OIDC Auth Code + PKCE)."
  type        = string
  default     = "https://target-app.localhost:8443/callback"
}

variable "device_login_admin_client_secret" {
  description = "Client secret for device-login-admin (KEYCLOAK_ADMIN_CLIENT_SECRET)."
  type        = string
  sensitive   = true
}

variable "device_login_idp_alias" {
  description = "Alias for the JWT Authorization Grant IdP (KEYCLOAK_IDP_ALIAS)."
  type        = string
  default     = "device-login-idp"
}

variable "device_login_client_id" {
  description = "Client ID of the device-login-client for trusted-client-ids config."
  type        = string
  default     = "device-login-client"
}

variable "jwt_issuer" {
  description = "Expected issuer (iss) in JWT tokens issued by auth-service (JWT_ISSUER)."
  type        = string
  default     = "https://auth-service.localhost:8443"
}

variable "device_login_jwks_url" {
  description = "JWKS URL of the auth-service, used by the device-login JWT IdP."
  type        = string
  default     = "http://auth-service:8083/api/v1/auth/.well-known/jwks.json"
}

variable "cms_client_secret" {
  description = "Client secret for cms-client (KEYCLOAK_CMS_CLIENT_SECRET)."
  type        = string
  sensitive   = true
}

variable "cms_callback_uri" {
  description = "Callback URI for cms-client."
  type        = string
  default     = "https://cms.localhost:8443/cms/callback"
}

variable "cms_public_redirect_uri" {
  description = "Redirect URI for cms-public-client."
  type        = string
  default     = "https://cms.localhost:8443/cms-content/*"
}

variable "cms_premium_redirect_uri" {
  description = "Redirect URI for cms-premium-client."
  type        = string
  default     = "https://cms.localhost:8443/cms-content/*"
}

variable "cms_admin_redirect_uri" {
  description = "Redirect URI for cms-admin-client."
  type        = string
  default     = "https://cms.localhost:8443/cms-content/*"
}

variable "cms_admin_sandbox_redirect_uri" {
  description = "Redirect URI for cms-admin-client in admin-sandbox realm."
  type        = string
  default     = "https://cms.localhost:8443/cms-admin/*"
}

variable "admin_client_secret" {
  description = "Client secret for admin-client (OIDC)."
  type        = string
  sensitive   = true
}

variable "admin_service_client_secret" {
  description = "Client secret for admin-service (token introspection)."
  type        = string
  sensitive   = true
}

variable "admin_realm_id" {
  description = "Name / ID of the admin realm."
  type        = string
  default     = "admin-sandbox"
}

variable "admin_client_id" {
  description = "Client ID for admin-client."
  type        = string
  default     = "admin-client"
}

variable "admin_redirect_uri" {
  description = "Redirect URI for admin-client."
  type        = string
  default     = "https://admin.localhost:8443/callback"
}
