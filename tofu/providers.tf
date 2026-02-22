provider "keycloak" {
  client_id = "admin-cli"
  username  = var.keycloak_admin_username
  password  = var.keycloak_admin_password
  url       = var.keycloak_url

  # Caddy uses a self-signed certificate for *.localhost
  tls_insecure_skip_verify = true
}
