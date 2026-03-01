# Setup Guide — auth-sandbox

> **Note:** This guide describes the current Podman Compose setup.
> The legacy Kubernetes/Kind manifests in `keycloak/` are kept for reference only — do not apply them.

Step-by-step instructions to bring up the complete local environment from scratch.

---

## Prerequisites

```bash
brew install podman podman-compose
```

Verify:

```bash
podman --version        # >= 5.x
podman-compose --version
```

---

## 1. /etc/hosts

Add the following entries (one-time, requires sudo):

```bash
echo "127.0.0.1  keycloak.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  auth-service.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  sso-proxy.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  app-mock.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  admin.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  target-app.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  home.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  cms.localhost" | sudo tee -a /etc/hosts
```

---

## 2. Secrets (.env)

Copy the example file and fill in all `change-me-*` values:

```bash
cp .env.example .env
```

Edit `.env` — at minimum replace every value that starts with `change-me-`.

---

## 3. RSA Keys (auth-service JWT signing)

Generate a 4096-bit RSA key pair for the `auth-service`:

```bash
mkdir -p auth-service/keys
openssl genrsa -out auth-service/keys/private.pem 4096
openssl rsa -in auth-service/keys/private.pem -pubout -out auth-service/keys/public.pem
```

These files are excluded from git (see `.gitignore`).

---

## 4. Build the Keycloak extension

The Keycloak SPI extension must be compiled before starting the stack:

```bash
cd keycloak-extension
./gradlew jar
cd ..
```

The built JAR will be picked up automatically by the `keycloak` service via the volume mount in `compose.yml`.

---

## 5. Start the stack

```bash
podman compose up -d
```

Services started:

| Service | Description |
|---|---|
| `postgres` | Shared PostgreSQL 16 (Keycloak + auth-service schemas) |
| `keycloak` | Keycloak 26.x in dev mode |
| `auth-service` | Spring Boot merged backend (device-login + SSO transfer + CMS) |
| `app-mock` | Browser mock of the mobile app |
| `admin-mock` | Browser admin panel |
| `target-app` | OIDC Auth Code + PKCE target SPA |
| `cms-admin` | CMS admin panel |
| `home` | Developer start page with links to all services |
| `caddy` | TLS-terminating reverse proxy for all `*.localhost` domains |

---

## 6. Keycloak Realm Setup (manual, one-time)

Open the Keycloak Admin UI at **https://keycloak.localhost:8443** and accept the self-signed certificate warning.

Log in with the credentials from your `.env` (`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`).

Create and configure the following (in order):

1. **Realm:** `auth-sandbox`
2. **Client** `device-login-admin` — Client Credentials grant; use `KEYCLOAK_ADMIN_CLIENT_SECRET` as the secret; assign the `realm-management` roles `view-users`, `manage-users`, `manage-identity-providers`
3. **Client** `device-login-client` — Standard Flow; use `KEYCLOAK_CLIENT_SECRET` as the secret; add `https://auth-service.localhost:8443/api/v1/auth/callback` as a valid redirect URI; also add `https://sso-proxy.localhost:8443/api/v1/transfer/callback` as a valid redirect URI
4. **JWT Authorization Grant IdP** `device-login-idp` (alias: value of `KEYCLOAK_IDP_ALIAS`):
   - Provider type: *JWT Authorization Grant (identity provider)*
   - JWKS URL: `https://auth-service.localhost:8443/api/v1/auth/.well-known/jwks.json`
   - Issuer: value of `JWT_ISSUER` from `.env`
5. **JWT Authorization Grant IdP** `sso-proxy-idp` (alias: value of `KEYCLOAK_SSO_PROXY_IDP_ALIAS`):
   - Provider type: *JWT Authorization Grant (identity provider)*
   - JWKS URL: `https://sso-proxy.localhost:8443/api/v1/transfer/.well-known/jwks.json`
   - Issuer: value of `JWT_ISSUER` from `.env`
6. **Authentication flow** using the `LoginTokenAuthenticator` SPI execution, bound to both IdPs

---

## 7. Access

| URL | Description |
|---|---|
| https://home.localhost:8443 | Developer start page (all links at a glance) |
| https://keycloak.localhost:8443 | Keycloak Admin UI |
| https://auth-service.localhost:8443/actuator/health | auth-service health check |
| https://admin.localhost:8443 | Admin mock panel |
| https://app-mock.localhost:8443 | Mobile app mock |
| https://target-app.localhost:8443 | SSO transfer target app |
| https://cms.localhost:8443 | CMS content pages |
| https://cms.localhost:8443/cms-admin/ | CMS admin panel |

---

## 8. Daily Workflow

```bash
# Start all services
podman compose up -d

# View logs
podman compose logs -f keycloak
podman compose logs -f auth-service

# Stop all services
podman compose down
```

---

## 9. Running the E2E tests

```bash
bash e2e-test.sh
```

Requires the full stack to be running and the Keycloak realm configured (step 6).

---

## Teardown

```bash
podman compose down -v   # also removes the postgres data volume
```
