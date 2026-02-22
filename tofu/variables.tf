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
  default     = "https://device-login.localhost:8443/api/v1/auth/callback"
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

variable "jwt_issuer" {
  description = "Expected issuer (iss) in device JWT tokens (JWT_ISSUER)."
  type        = string
  default     = "https://device-login.localhost:8443"
}

variable "device_login_jwks_url" {
  description = "JWKS URL of the device-login service, used by the JWT IdP."
  type        = string
  default     = "https://device-login.localhost:8443/api/v1/auth/.well-known/jwks.json"
}
