# AGENTS.md — auth-sandbox

Guidance for AI coding agents working in this repository.

---

## Project Overview

This is a **mobile authentication project** implementing an OAuth2-style Device Authorization Grant flow using Keycloak as the Identity Provider (IAM), a custom Device Login backend, and a mobile app with a local cryptographic keystore (biometric/PIN-protected).

**Infrastructure target:** Podman Compose (local development) — previously Kind/Kubernetes, migrated to `podman compose`.

---

## Repository Structure

```
auth-sandbox/
├── c4-spec/              # LikeC4 architecture diagrams
│   ├── _spec.c4          # Element type definitions and visual styles
│   ├── model.c4          # System model: actors, components, relationships
│   ├── deployment.c4     # Deployment topology (Podman Compose)
│   └── views.c4          # Architecture views (index, deployment, dynamic)
├── device-login/         # Spring Boot 3 / Java 21 backend service
│   ├── Dockerfile            # Multi-stage build (Gradle + Temurin JRE)
│   ├── build.gradle          # Gradle build config
│   ├── keys/                 # RSA PEM key pair for JWT signing (not committed)
│   │   ├── private.pem
│   │   └── public.pem
│   ├── k8s/                  # Legacy Kubernetes manifests (reference only)
│   └── src/                  # Application source
├── keycloak-extension/   # Keycloak SPI authenticator (Java/Gradle)
│   ├── build.gradle          # Gradle build config
│   ├── settings.gradle       # Gradle settings
│   └── src/                  # SPI source (LoginTokenAuthenticator)
├── keycloak/             # Legacy Kubernetes manifests (kept for reference)
│   ├── namespace.yaml        # keycloak namespace
│   ├── postgres-secret.yaml  # PostgreSQL credentials (dev only)
│   ├── keycloak-secret.yaml  # Keycloak admin credentials (dev only)
│   ├── postgres.yaml         # PostgreSQL PVC, Deployment, Service
│   ├── keycloak.yaml         # Keycloak Deployment, Service
│   ├── ingress.yaml          # Ingress rule → keycloak.localhost
│   ├── cluster-issuer.yaml   # cert-manager ClusterIssuer (self-signed CA)
│   └── proxy-headers.yaml    # ConfigMap for nginx proxy headers
├── app-mock-react/       # React/TypeScript/Vite/Tailwind mock of the mobile app
│   ├── dist/                 # Built output served by Caddy (run npm run build)
│   └── src/                  # App source (screens, components, services, hooks, types)
├── admin-mock-react/     # React/TypeScript/Vite/Tailwind admin panel
│   ├── dist/                 # Built output served by Caddy (run npm run build)
│   └── src/                  # Admin source (App.tsx, components, services, hooks, types)
├── home/                 # Developer start page (static HTML with links to all services)
├── tofu/                 # OpenTofu (Terraform) configuration for Keycloak realm setup
│   ├── realm.tf              # Realm, clients, IDP, auth flow definitions
│   ├── idp_and_flow.tf       # Identity provider and authentication flow
│   ├── providers.tf          # Keycloak provider config
│   ├── variables.tf          # Input variables
│   ├── outputs.tf            # Output values
│   ├── versions.tf           # Provider version constraints
│   └── terraform.tfvars.example  # Variable template
├── compose.yml           # Podman Compose stack (7 services)
├── Caddyfile             # Caddy reverse proxy config (TLS termination for *.localhost)
├── e2e-test.sh           # End-to-end test script (curl + openssl)
├── postgres-init/        # SQL/shell scripts run by postgres on first start
│   └── 01-device-login.sh    # Creates device_login user + schema
├── .env                  # Local secrets — NOT committed to git
├── .env.example          # Secret template with placeholder values
├── SETUP.md              # Setup guide
└── project.md            # Minimal project note
```

---

## Architecture: Key Components

| Component | Kind | Description |
|---|---|---|
| `mobile_app` | mobileapp | Mobile client; contains `keystore` (crypto keys) and `login` (auth flow) |
| `device_login` | backend | Issues and verifies device JWT tokens; has own PostgreSQL DB (`device_db`) |
| `keycloak` | backend | IAM; contains `device_token_ext` (custom KC extension) and `user_db` |

**Authentication flow** (see `c4-spec/views.c4` dynamic view `app_login`):
1. User initiates login in the mobile app
2. `mobile_app.login` → `device_login`: start login, receive challenge
3. `mobile_app.keystore`: sign challenge (biometric/PIN gate)
4. `device_login`: verify signature, issue JWT device token
5. `keycloak.device_token_ext`: exchange device token for OIDC tokens (Code Auth Flow)
6. OIDC tokens returned to mobile app — user authenticated

---

## Build / Lint / Test Commands

### app-mock-react / admin-mock-react (React/TypeScript/Vite)

```bash
# Build app-mock (output → app-mock-react/dist, served by Caddy)
cd app-mock-react && npm run build

# Build admin-mock (output → admin-mock-react/dist, served by Caddy)
cd admin-mock-react && npm run build
```

No container restart is needed after rebuilding — Caddy serves `dist/` via a volume mount.

### device-login (Spring Boot / Gradle)

```bash
# Build (produces device-login/build/libs/device-login-*.jar)
cd device-login && ./gradlew bootJar

# Run tests
cd device-login && ./gradlew test

# Run a single test class
cd device-login && ./gradlew test --tests "dev.authsandbox.devicelogin.service.JwtServiceTest"
```

### keycloak-extension (Gradle)

```bash
# Build the provider JAR (required before starting the stack)
cd keycloak-extension && ./gradlew jar
```

### LikeC4 (C4 Architecture Diagrams)

The `.c4` files use the [LikeC4](https://likec4.dev/) DSL. To work with diagrams:

```bash
# Install LikeC4 CLI (requires Node.js)
npx likec4 --help

# Preview diagrams interactively in the browser
npx likec4 serve c4-spec/

# Validate/lint all .c4 files
npx likec4 validate c4-spec/

# Export diagrams as PNG/SVG
npx likec4 export png c4-spec/ --output ./diagrams/
```

There are currently **no automated tests** for the React frontends. When tests are added, this section must be updated with:
- How to run the full test suite
- How to run a single test (e.g., `npx vitest run src/foo.test.ts`)

---

## C4 / LikeC4 DSL Style Guidelines

These conventions apply to all `.c4` files.

### File Organization

- `_spec.c4` — element type and style definitions only; no model elements
- `model.c4` — all model elements and relationships; one `model { }` block
- `deployment.c4` — deployment topology; one `deployment { }` block
- `views.c4` — all views; one `views { }` block
- Keep files focused; do not mix concerns across files

### Naming Conventions

- Use `snake_case` for all element identifiers (e.g., `device_login`, `mobile_app`, `keycloak_postgres_db`)
- Use descriptive, unambiguous names that reflect the component's role
- Deployment pod names must match or clearly correspond to the model element they instantiate

### Relationships

- Use `->` for directed relationships
- Always include a short inline label on the relationship
- Use triple-quoted block strings (`''' ... '''`) for multi-line descriptions:
  ```
  -> other_element "Short label" {
      description '''
      Longer explanation of the relationship semantics.
      '''
  }
  ```

### Views

- `include *` for overview views (catches all top-level elements)
- `include element._` to include direct children of a specific element
- Dynamic views must list all involved elements in the closing `include` statement
- Give every non-index view an explicit `title`

### Element Types (from `_spec.c4`)

| Type | Shape | Use for |
|---|---|---|
| `actor` | person | Human users |
| `backend` | default | Server-side services |
| `system` | default | External systems |
| `component` | component | Internal sub-components |
| `kc_extension` | component | Keycloak extensions |
| `kc_idp` | component | Keycloak IDP configurations |
| `webapp` | browser | Web frontends |
| `mobileapp` | mobile | Mobile clients |
| `database` | storage | Databases and persistent stores |

Deployment node types: `pod`, `kubernetes_cluster`

---

## When Writing Application Code

The following services are implemented:

- **app-mock-react** — React/TypeScript/Vite/Tailwind mock of the mobile app (browser-based flow testing)
- **admin-mock-react** — React/TypeScript/Vite/Tailwind admin panel (registration code management, Keycloak sync)
- **device-login** — Spring Boot 3 / Java 21 backend (device registration, JWT issuance, admin API)
- **keycloak-extension** — Java Keycloak SPI (`LoginTokenAuthenticator`) for the JWT Authorization Grant flow
- **Infrastructure** — Podman Compose (`compose.yml`) with Caddy as TLS-terminating reverse proxy; OpenTofu (`tofu/`) for Keycloak realm setup

When adding a new service or language, update this file with:
1. The language/framework chosen
2. Build and run commands
3. Test commands (full suite and single-test invocation)
4. Lint/format commands and config file locations
5. Code style conventions for that language

### Domain Rules (device-login)

- **Keycloak username always equals `userId`** (the pre-provisioned string) — basis for all username lookups
- `keycloakUserId` is **not** stored on `registration_codes` — Keycloak existence is always checked by username lookup on demand
- `Device.keycloakUserId` is stored (it IS persisted on devices after registration)
- A Keycloak user is created immediately when an admin creates a registration code (`AdminService.createRegistrationCode`)
- During device registration (`DeviceService`), Keycloak user lookup uses `getUserIdByUsername` and recreates the user if it was deleted
- LSP errors in Java files are typically Lombok artefacts — `./gradlew test` is the source of truth for correctness

---

## General Coding Conventions (to apply when code is written)

### Imports

- Group imports: standard library → third-party → internal; blank line between groups
- No unused imports; remove them before committing

### Error Handling

- Errors must be handled explicitly; do not silently swallow errors
- Log errors with sufficient context to diagnose the problem
- Surface errors to callers rather than exiting deep in library code

### Naming

- Prefer clarity over brevity; avoid single-letter names outside loop indices
- Boolean variables and functions should read as predicates (`isAuthenticated`, `hasExpired`)
- Constants in `UPPER_SNAKE_CASE`, types/classes in `PascalCase`, functions/variables in `camelCase` (JS/TS) or `snake_case` (Go/Rust/Python)

### Security (critical for an auth project)

- Never log secrets, tokens, passwords, or private keys
- Never commit secrets — use environment variables or a secrets manager
- Cryptographic operations must use well-vetted libraries; do not implement crypto primitives yourself
- JWT validation must always verify signature, expiry (`exp`), audience (`aud`), and issuer (`iss`)
- Device tokens are sensitive; treat them with the same care as OIDC access tokens

### Git

- Commits should be atomic and focused; one logical change per commit
- Write commit messages in the imperative mood: `Add device token validation endpoint`
- Do not commit generated files, build artifacts, or secrets

---

## Infrastructure Notes

- Local stack runs with **Podman Compose** (`compose.yml`)
- **Seven services:** `postgres`, `keycloak`, `device-login`, `app-mock`, `admin-mock`, `home`, `caddy`
- **Single PostgreSQL instance, two schemas:** `public` (Keycloak default) and `device_login` (device-login service)
- `postgres-init/01-device-login.sh` runs on first DB start and creates the `device_login` user + schema
- Caddy handles TLS termination for all `*.localhost` domains (self-signed certificate via `tls internal`)
- Each future service gets its own schema inside the shared DB (no separate DB containers)
- Keycloak's custom extension (`device_token_ext`) is the only integration point between `device_login` and Keycloak

### Stack Setup Commands

```bash
# Copy and adjust secrets
cp .env.example .env   # if provided; otherwise edit .env directly

# Start all services
podman compose up -d

# Stop all services
podman compose down

# View logs
podman compose logs -f keycloak
```

### Access

- Keycloak Admin UI: **https://keycloak.localhost:8443** (admin / admin-password)
- device-login API: **https://device-login.localhost:8443**
- App mock (mobile flow): **https://app-mock.localhost:8443**
- Admin panel: **https://admin.localhost:8443**
- Developer start page: **https://home.localhost:8443**
- Caddy issues a self-signed certificate automatically; add a trust exception in your browser
- Add to `/etc/hosts` if not already resolved:
  ```
  127.0.0.1  keycloak.localhost
  127.0.0.1  device-login.localhost
  127.0.0.1  app-mock.localhost
  127.0.0.1  admin.localhost
  127.0.0.1  home.localhost
  ```

### Secrets (`.env`)

The `.env` file is **not committed** (listed in `.gitignore`). Required variables:

| Variable | Description |
|---|---|
| `POSTGRES_DB` | Keycloak database name |
| `POSTGRES_USER` | PostgreSQL username for Keycloak |
| `POSTGRES_PASSWORD` | PostgreSQL password for Keycloak |
| `KEYCLOAK_ADMIN` | Keycloak admin username |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak admin password |
| `DEVICE_POSTGRES_USER` | PostgreSQL username for device-login (schema: `device_login`) |
| `DEVICE_POSTGRES_PASSWORD` | PostgreSQL password for device-login |
| `JWT_ISSUER` | JWT `iss` claim for device tokens |
| `JWT_EXPIRATION_SECONDS` | Device token TTL (default: 300) |
| `CHALLENGE_EXPIRATION_SECONDS` | Challenge TTL (default: 120) |
| `KEYCLOAK_REALM_URL` | Public realm URL (used in tokens) |
| `KEYCLOAK_AUTH_ENDPOINT` | OIDC authorization endpoint (internal) |
| `KEYCLOAK_TOKEN_ENDPOINT` | OIDC token endpoint (internal) |
| `KEYCLOAK_CLIENT_ID` | OIDC client ID for device-login auth flow |
| `KEYCLOAK_CLIENT_SECRET` | OIDC client secret |
| `KEYCLOAK_REDIRECT_URI` | OAuth2 redirect URI for auth callback |
| `KEYCLOAK_SCOPE` | OIDC scopes (default: `openid profile`) |
| `KEYCLOAK_ASSERTION_EXPIRATION_SECONDS` | JWT assertion TTL for token exchange (default: 60) |
| `KEYCLOAK_ADMIN_CLIENT_ID` | Keycloak admin API client ID |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | Keycloak admin API client secret |
| `KEYCLOAK_ADMIN_REALM` | Realm used for admin API token requests |
| `KEYCLOAK_IDP_ALIAS` | Alias of the device-login Identity Provider in Keycloak |
| `KEYCLOAK_ADMIN_TOKEN_ENDPOINT` | Token endpoint for admin API (internal URL) |
| `KEYCLOAK_ADMIN_BASE_URL` | Keycloak base URL for admin REST API (internal) |

### Legacy Kubernetes Manifests

The `keycloak/` directory contains the original Kubernetes manifests and is kept for reference only.
Do **not** apply them to the local environment — use `compose.yml` instead.
