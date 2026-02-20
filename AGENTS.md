# AGENTS.md — auth-sandbox

Guidance for AI coding agents working in this repository.

---

## Project Overview

This is a **greenfield mobile authentication project** at the architecture specification stage. The intended system implements an OAuth2-style Device Authorization Grant flow using Keycloak as the Identity Provider (IAM), a custom Device Login backend, and a mobile app with a local cryptographic keystore (biometric/PIN-protected).

**Infrastructure target:** Podman + Kind (Kubernetes-in-Docker) running locally.

No application code exists yet. The repository currently contains only C4 architecture diagrams.

---

## Repository Structure

```
auth-sandbox/
├── c4-spec/              # LikeC4 architecture diagrams
│   ├── _spec.c4          # Element type definitions and visual styles
│   ├── model.c4          # System model: actors, components, relationships
│   ├── deployment.c4     # Deployment topology (Kubernetes pods)
│   └── views.c4          # Architecture views (index, deployment, dynamic)
├── keycloak/             # Kubernetes manifests for Keycloak and its database
│   ├── namespace.yaml        # keycloak namespace
│   ├── postgres-secret.yaml  # PostgreSQL credentials (dev only)
│   ├── keycloak-secret.yaml  # Keycloak admin credentials (dev only)
│   ├── postgres.yaml         # PostgreSQL PVC, Deployment, Service
│   ├── keycloak.yaml         # Keycloak Deployment, Service
│   └── ingress.yaml          # Ingress rule → keycloak.localhost
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

> No build system exists yet. When one is introduced, commands should be documented here.

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

There are currently **no automated tests**. When tests are added, this section must be updated with:
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

The project will likely grow to include the following services (based on the architecture spec):

- **Mobile app** — likely React Native or Flutter
- **Device Login backend** — likely a Go, Rust, or JVM (Kotlin/Java) service
- **Keycloak extension** — Java/Kotlin (standard Keycloak SPI)
- **Infrastructure** — Podman + Kind + Kubernetes manifests or Helm charts

When adding a new service or language, update this file with:
1. The language/framework chosen
2. Build and run commands
3. Test commands (full suite and single-test invocation)
4. Lint/format commands and config file locations
5. Code style conventions for that language

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

- Local cluster runs on **Podman** with **Kind** (`podman_kind` in the deployment model)
- Podman machine name: `auth-sandbox` (4 CPUs, 4 GiB RAM, 60 GiB disk, rootful mode)
- Kind cluster name: `auth-sandbox` — kubectl context: `kind-auth-sandbox`
- Four pods: `keycloak`, `device_login`, `keycloak_postgres_db`, `device_postgres_db`
- Each service connects only to its own database pod (no cross-service DB access)
- Keycloak's custom extension (`device_token_ext`) is the only integration point between `device_login` and Keycloak

### Cluster Setup Commands

```bash
# Start the Podman machine (after a reboot)
podman machine start auth-sandbox

# Apply all Keycloak manifests
kubectl apply -f keycloak/ --context kind-auth-sandbox

# Access Keycloak locally via port-forward
kubectl port-forward -n keycloak svc/keycloak 8080:80 --context kind-auth-sandbox
# Then open: http://keycloak.localhost:8080  (admin / admin-password)

# Alternatively, use the ingress NodePort directly
# NodePort 80 → 30489 on the Kind node IP (10.89.0.2 by default)
```

### Ingress

- Ingress class: `nginx` (ingress-nginx installed in `ingress-nginx` namespace)
- Hostname: `keycloak.localhost`
- The Kind cluster was created without host port-mapping, so the ingress is reached via
  `kubectl port-forward` on the ingress-nginx controller, or directly via the NodePort.
- To reach `keycloak.localhost` transparently, add to `/etc/hosts`:
  ```
  127.0.0.1  keycloak.localhost
  ```
  Then run: `kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 80:80 --context kind-auth-sandbox`
