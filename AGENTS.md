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

# React app UI tests (Playwright)
cd app-mock-react && npm test                 # run all tests
cd app-mock-react && npm run test:ui          # run with UI
cd admin-mock-react && npm test
cd admin-mock-react && npm run test:ui
cd target-app-react && npm test
cd target-app-react && npm run test:ui

# auth-service
cd auth-service && ./gradlew bootJar          # build JAR locally (REQUIRED before Docker build)
podman compose build auth-service              # build Docker image (uses pre-built JAR)
cd auth-service && ./gradlew test             # run all tests
cd auth-service && ./gradlew test --tests "dev.authsandbox.authservice.service.JwtServiceTest"

# keycloak-extension (build before starting the stack)
cd keycloak-extension && ./gradlew jar

# E2E tests (TypeScript + Vitest + Testcontainers — spins up the full stack)
cd e2e && npm test                            # run all suites
cd e2e && npm test -- --reporter=verbose      # verbose output
```

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

<!-- BEGIN BEADS INTEGRATION -->
## Issue Tracking with bd (beads)

**IMPORTANT**: This project uses **bd (beads)** for ALL issue tracking. Do NOT use markdown TODOs, task lists, or other tracking methods.

### Why bd?

- Dependency-aware: Track blockers and relationships between issues
- Git-friendly: Dolt-powered version control with native sync
- Agent-optimized: JSON output, ready work detection, discovered-from links
- Prevents duplicate tracking systems and confusion

### Quick Start

**Check for ready work:**

```bash
bd ready --json
```

**Create new issues:**

```bash
bd create "Issue title" --description="Detailed context" -t bug|feature|task -p 0-4 --json
bd create "Issue title" --description="What this issue is about" -p 1 --deps discovered-from:bd-123 --json
```

**Claim and update:**

```bash
bd update <id> --claim --json
bd update bd-42 --priority 1 --json
```

**Complete work:**

```bash
bd close bd-42 --reason "Completed" --json
```

### Issue Types

- `bug` - Something broken
- `feature` - New functionality
- `task` - Work item (tests, docs, refactoring)
- `epic` - Large feature with subtasks
- `chore` - Maintenance (dependencies, tooling)

### Priorities

- `0` - Critical (security, data loss, broken builds)
- `1` - High (major features, important bugs)
- `2` - Medium (default, nice-to-have)
- `3` - Low (polish, optimization)
- `4` - Backlog (future ideas)

### Workflow for AI Agents

1. **Check ready work**: `bd ready` shows unblocked issues
2. **Claim your task atomically**: `bd update <id> --claim`
3. **Work on it**: Implement, test, document
4. **Discover new work?** Create linked issue:
   - `bd create "Found bug" --description="Details about what was found" -p 1 --deps discovered-from:<parent-id>`
5. **Complete**: `bd close <id> --reason "Done"`

### Auto-Sync

bd automatically syncs via Dolt:

- Each write auto-commits to Dolt history
- Use `bd dolt push`/`bd dolt pull` for remote sync
- No manual export/import needed!

### Important Rules

- ✅ Use bd for ALL task tracking
- ✅ Always use `--json` flag for programmatic use
- ✅ Link discovered work with `discovered-from` dependencies
- ✅ Check `bd ready` before asking "what should I work on?"
- ❌ Do NOT create markdown TODO lists
- ❌ Do NOT use external issue trackers
- ❌ Do NOT duplicate tracking systems

For more details, see README.md and docs/QUICKSTART.md.

<!-- END BEADS INTEGRATION -->

## Landing the Plane (Session Completion)

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd sync
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
