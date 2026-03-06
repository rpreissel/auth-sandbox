# AGENTS.md — auth-sandbox

Guidelines for AI coding agents working in this repository.

---

## Development Rules

- **IMMER ein Bead anlegen, bevor mit der Implementierung gestartet wird** – Kein Code schreiben, bevor kein Issue existiert:
  ```bash
  bd create "Kurztitel" --description="Was gemacht werden soll" -t feature|bug|task -p 0-4
  bd update <id> --status in_progress
  ```
  **Regel: Erst bead-create, DANN Code schreiben, NACHHER committen.**
- **Jedes neue Feature braucht einen E2E-Test** – Vor dem Commit prüfen ob E2E-Tests vorhanden sind.

---

## Domain Rules

- **Keycloak username always equals `userId`** — basis for all username lookups
- `keycloakUserId` is **not** stored on `registration_codes` — always looked up on demand
- `Device.keycloakUserId` IS persisted after registration
- Keycloak user is created immediately when admin creates a registration code (`AdminService.createRegistrationCode`)
- During device registration, Keycloak user lookup uses `getUserIdByUsername` and recreates the user if deleted
- LSP errors in Java files are typically Lombok artefacts — `./gradlew test` is the source of truth

---

## Infrastructure

- **Services:** `postgres`, `keycloak`, `auth-service`, `caddy` (+ frontend apps via volumes)
- **DB:** Single PostgreSQL instance, two schemas: `public` (Keycloak) and `device_login` (auth-service)
- **TLS:** Caddy handles all `*.localhost` domains (`tls internal`)
- **Integration:** `device_token_ext` SPI is the only integration point between `auth-service` and Keycloak

See [README.md](./README.md) for architecture details and [SETUP.md](./SETUP.md) for setup instructions.

---

## Issue Tracking (bd)

**IMPORTANT**: Use **bd (beads)** for ALL task tracking.

```bash
bd ready --json                    # Check for available work
bd create "Title" -t feature -p 2  # Create issue
bd update <id> --status in_progress # Claim work
bd close <id> --reason "Done"      # Complete
```

### Priorities
- `0` - Critical, `1` - High, `2` - Medium, `3` - Low, `4` - Backlog

---

## Session Completion

1. **File issues** for remaining work
2. **Run quality gates** (tests, linters, builds)
3. **Update issue status**
4. **PUSH TO REMOTE:**
   ```bash
   git pull --rebase
   bd sync
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Verify** all changes committed and pushed
