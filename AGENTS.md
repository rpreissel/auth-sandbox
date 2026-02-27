# AGENTS.md — auth-sandbox

Guidance for AI coding agents working in this repository.

---

## Project Overview

Mobile authentication project implementing two flows:
1. **Device Authorization Grant** — mobile app registers a device, signs a challenge with a biometric/PIN-protected key, receives a JWT device token, which Keycloak exchanges for OIDC tokens via a custom SPI extension.
2. **SSO Transfer** — browser-based SSO handoff: an existing OIDC session is transferred to a second app using a short-lived transfer token and Keycloak's PAR + JWT Authorization Grant features.

Both flows are served by **auth-service** (Spring Boot 3 / Java 21). Infrastructure: **Podman Compose** (`compose.yml`).

---

## Repository Structure

| Directory / File | Purpose |
|---|---|
| `auth-service/` | Spring Boot 3 / Java 21 — merged backend (device-login + sso-proxy) |
| `keycloak-extension/` | Keycloak SPI (`LoginTokenAuthenticator`) |
| `app-mock-react/` | React/TS/Vite/Tailwind — mobile app mock |
| `admin-mock-react/` | React/TS/Vite/Tailwind — admin panel |
| `target-app-react/` | React/TS/Vite/Tailwind — OIDC Auth Code + PKCE target SPA |
| `c4-spec/` | LikeC4 architecture diagrams (`_spec.c4`, `model.c4`, `deployment.c4`, `views.c4`) |
| `tofu/` | OpenTofu (Terraform) — Keycloak realm setup |
| `keycloak/` | **LEGACY** Kubernetes manifests — reference only, do not apply |
| `compose.yml` | Podman Compose stack (8 services) |
| `Caddyfile` | Caddy reverse proxy — TLS termination for `*.localhost` |
| `.env` / `.env.example` | Secrets — `.env` is **not committed**; see `.env.example` for all variables |
| `SETUP.md` | Setup guide (access URLs, `/etc/hosts`, first-run steps) |

`auth-service/src/main/java/dev/authsandbox/authservice/` packages:
- `config/` — AppConfig, JwtProperties, ChallengeProperties, KeycloakProperties, KeycloakAdminProperties, SecurityConfig
- `controller/` — AuthController, DeviceController, AdminController, TransferController, JwksController, GlobalExceptionHandler
- `service/` — JwtService, AuthService, DeviceService, AdminService, TransferService, KeycloakAdminClient, KeycloakAuthClient, KeycloakTransferClient
- `entity/` — Challenge, Device, RegistrationCode, TransferSession
- `dto/` — 15 request/response records
- `db/migration/` — Flyway V1–V9

---

## Architecture: Key Components

| Component | Kind | Description |
|---|---|---|
| `mobile_app` | mobileapp | Mobile client; contains `keystore` (crypto keys) and `login` (auth flow) |
| `auth_service` | backend | Merged backend; PostgreSQL schema `device_login` |
| `keycloak` | backend | IAM; contains `device_token_ext` (custom SPI) and `user_db` |

**Device Authorization flow:**
1. User initiates login → `auth-service`: receive challenge
2. Mobile keystore signs challenge (biometric/PIN gate)
3. `auth-service` verifies signature, issues JWT device token
4. `keycloak.device_token_ext` exchanges device token for OIDC tokens
5. OIDC tokens returned to mobile app

**SSO Transfer flow:**
1. Source app calls `POST /api/v1/transfer/init` with OIDC access token
2. `auth-service` introspects token, ensures `sso-proxy-idp` federated identity in Keycloak, issues transfer token, pushes PAR, returns redirect URI
3. Browser → Keycloak → authenticated via transfer token
4. Keycloak calls back `GET /api/v1/transfer/callback`; `auth-service` exchanges code for OIDC tokens

---

## Build / Test Commands

```bash
# React apps (app-mock, admin-mock, target-app) — no container restart needed (Caddy serves dist/)
cd app-mock-react && npm run build
cd admin-mock-react && npm run build
cd target-app-react && npm run build

# auth-service
cd auth-service && ./gradlew bootJar          # build JAR
cd auth-service && ./gradlew test             # run all tests
cd auth-service && ./gradlew test --tests "dev.authsandbox.authservice.service.JwtServiceTest"

# keycloak-extension (build before starting the stack)
cd keycloak-extension && ./gradlew jar
```

No automated tests for React frontends. When added, update this file with: full suite command, single-test command, lint/format commands.

LikeC4 diagrams: see `c4-spec/STYLE.md` for DSL conventions and CLI commands.

---

## Domain Rules (device-login)

- **Keycloak username always equals `userId`** — basis for all username lookups
- `keycloakUserId` is **not** stored on `registration_codes` — always looked up on demand
- `Device.keycloakUserId` IS persisted after registration
- Keycloak user is created immediately when admin creates a registration code (`AdminService.createRegistrationCode`)
- During device registration, Keycloak user lookup uses `getUserIdByUsername` and recreates the user if deleted
- LSP errors in Java files are typically Lombok artefacts — `./gradlew test` is the source of truth

---

## Security Conventions

- Never log secrets, tokens, passwords, or private keys
- Never commit secrets — use environment variables
- Cryptographic operations must use well-vetted libraries; no custom crypto primitives
- JWT validation must always verify signature, `exp`, `aud`, and `iss`
- Device tokens are sensitive; treat them with the same care as OIDC access tokens

---

## Infrastructure Notes

- **8 services:** `postgres`, `keycloak`, `auth-service`, `app-mock`, `admin-mock`, `target-app`, `home`, `caddy`
- **Single PostgreSQL instance, two schemas:** `public` (Keycloak) and `device_login` (auth-service)
- `postgres-init/01-device-login.sh` creates the `device_login` user + schema on first DB start
- Caddy handles TLS for all `*.localhost` domains (`tls internal`)
- Future services get their own schema inside the shared DB
- `device_token_ext` is the only integration point between `auth-service` and Keycloak
- `keycloak/` (Kubernetes manifests) — **do not apply locally**, use `compose.yml`
- All secrets: see `.env.example`; access URLs and `/etc/hosts` setup: see `SETUP.md`

### New service checklist

When adding a service, update this file with: language/framework, build/run commands, test commands, lint/format commands, code style conventions.
