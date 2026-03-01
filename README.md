# auth-sandbox

A mobile authentication project implementing device authorization with biometric/PIN-protected keys and a CMS mock system with role-based content protection.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Podman Compose Stack                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────┐    ┌──────────────┐    ┌─────────────────┐                   │
│  │  mobile  │    │ auth-service  │    │    keycloak    │                   │
│  │   app    │◄──►│  (Spring Boot │◄──►│     (IAM)      │                   │
│  │ (mock)   │    │    3 / Java)  │    │                │                   │
│  └──────────┘    └───────┬───────┘    └───────┬────────┘                   │
│                          │                    │                             │
│                          │                    │  ┌──────────────────────┐  │
│                          │                    └──►  device_token_ext   │  │
│                          │         (SPI)           │  (LoginTokenAuth)   │  │
│                          │                         └──────────────────────┘  │
│                          │                                                      │
│  ┌──────────┐    ┌──────┴───────┐    ┌─────────────┐                         │
│  │ target-  │    │   postgres   │    │   caddy     │                         │
│  │   app    │    │ (16 + schemas)   │  (TLS proxy) │                         │
│  └──────────┘    └───────────────┘    └──────┬──────┘                         │
│                                              │                                │
│  ┌──────────┐    ┌───────────────┐          │                                │
│  │ admin-   │    │  cms-admin    │◄─────────┘                                │
│  │  mock    │    │   (React)     │                                           │
│  └──────────┘    └───────────────┘                                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Services

| Service | Port | Description |
|---------|------|-------------|
| `postgres` | 5432 | Shared PostgreSQL 16 (Keycloak + auth-service schemas) |
| `keycloak` | 8080/8443 | Keycloak 26.x IAM |
| `auth-service` | 8083 | Spring Boot backend (device-login + SSO + CMS) |
| `app-mock` | 5173 | Browser mock of the mobile app |
| `admin-mock` | 5173 | Admin panel |
| `target-app` | 5173 | OIDC Auth Code + PKCE target SPA |
| `cms-admin` | 5173 | CMS admin panel |
| `home` | 5173 | Developer start page |
| `caddy` | 80/443 | TLS-terminating reverse proxy |

---

# Authentication Flows

## Flow 1: Device Authorization Grant

Mobile app registers a device, signs a challenge with a biometric/PIN-protected key, receives a JWT device token, which Keycloak exchanges for OIDC tokens via a custom SPI extension.

```
┌─────────────┐     ┌─────────────────┐     ┌────────────────────┐     ┌─────────────┐
│   Mobile    │     │  auth-service  │     │     keycloak      │     │   Mobile   │
│    App      │     │  (Spring Boot) │     │                   │     │    App     │
└──────┬──────┘     └────────┬────────┘     └─────────┬────────┘     └──────┬──────┘
       │                     │                          │                     │
       │  1. POST /challenge │                          │                     │
       │ ──────────────────► │                          │                     │
       │                     │                          │                     │
       │  2. Challenge (JWT) │                          │                     │
       │ ◄────────────────── │                          │                     │
       │                     │                          │                     │
       │  3. Sign with       │                          │                     │
       │     biometric/PIN   │                          │                     │
       │     (local)        │                          │                     │
       │                     │                          │                     │
       │  4. POST /register │                          │                     │
       │     + signature    │                          │                     │
       │ ──────────────────► │                          │                     │
       │                     │                          │                     │
       │                     │  5. Verify signature    │                     │
       │                     │ ◄──────────────────────► │                     │
       │                     │                          │                     │
       │                     │  6. JWT Device Token     │                     │
       │                     │ ◄─────────────────────── │                     │
       │                     │                          │                     │
       │                     │                          │  7. Auth with      │
       │                     │                          │     device token   │
       │                     │                          │ ─────────────────► │
       │                     │                          │                     │
       │                     │                          │  8. Validate JWT  │
       │                     │                          │ ◄────────────────► │
       │                     │                          │                     │
       │                     │                          │  9. OIDC Tokens   │
       │                     │                          │ ◄────────────────── │
       │                     │                          │                     │
       │                     │                          │ 10. OIDC Tokens   │
       │                     │                          │ ◄────────────────── │
       │                     │                          │                     │
       │ 11. OIDC Tokens    │                          │                     │
       │ ◄────────────────── │                          │                     │
       │                     │                          │                     │
```

### Key Components

| Component | Description |
|-----------|-------------|
| `mobile_app` | Mobile client with keystore (crypto keys) and login flow |
| `auth_service` | Merged backend; PostgreSQL schema `device_login` |
| `keycloak` | IAM with `device_token_ext` (custom SPI) and `user_db` |

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/device/challenge` | Receive cryptographic challenge |
| POST | `/api/v1/device/register` | Register device with signed challenge |
| POST | `/api/v1/device/login` | Login with registered device |
| GET | `/api/v1/auth/.well-known/jwks.json` | JWKS for token verification |

---

## Flow 2: SSO Transfer

Browser-based SSO handoff: an existing OIDC session is transferred to a second app using a short-lived transfer token and Keycloak's PAR + JWT Authorization Grant features.

```
┌─────────────┐     ┌─────────────────┐     ┌────────────────────┐
│   Browser   │     │  auth-service   │     │     keycloak       │
│   (App A)   │     │  (SSO Proxy)    │     │                    │
└──────┬──────┘     └────────┬────────┘     └─────────┬────────┘
       │                     │                          │
       │  1. POST /transfer/init                        │
       │     + access_token     │                          │
       │ ──────────────────────► │                          │
       │                     │                          │                     │
       │                     │  2. Introspect token     │                     │
       │                     │ ◄───────────────────────► │                     │
       │                     │                          │                     │
       │                     │  3. Ensure federated     │                     │
       │                     │     identity in KC      │                     │
       │                     │ ◄───────────────────────► │                     │
       │                     │                          │                     │
       │                     │  4. Issue transfer token │                     │
       │                     │ ◄─────────────────────── │                     │
       │                     │                          │                     │
       │                     │  5. PAR (Pushed Auth    │                     │
       │                     │     Request)            │                     │
       │                     │ ◄───────────────────────► │                     │
       │                     │                          │                     │
       │                     │  6. Redirect URI        │                     │
       │ ◄─────────────────── │ ◄─────────────────────── │                     │
       │                     │                          │                     │
       │  7. Browser → Keycloak (transfer token)        │
       │ ─────────────────────────────────────────────► │
       │                     │                          │                     │
       │                     │                          │  8. Validate       │
       │                     │                          │     transfer token │
       │                     │                          │ ◄────────────────► │
       │                     │                          │                     │
       │                     │                          │ 9. Auth Code       │
       │                     │                          │ ◄────────────────► │
       │                     │                          │                     │
       │ 10. Callback with auth code                   │
       │ ─────────────────────────────────────────────► │
       │                     │                          │                     │
       │                     │ 11. Exchange code       │                     │
       │                     │     for tokens          │                     │
       │                     │ ◄───────────────────────► │                     │
       │                     │                          │                     │
       │                     │ 12. OIDC Tokens         │                     │
       │                     │ ◄─────────────────────── │                     │
       │                     │                          │                     │
       │                     │ 13. Redirect to App B   │                     │
       │ ◄─────────────────── │                          │                     │
       │                     │                          │                     │
```

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/transfer/init` | Initialize SSO transfer |
| GET | `/api/v1/transfer/callback` | Keycloak callback with auth code |
| GET | `/api/v1/transfer/.well-known/jwks.json` | JWKS for transfer tokens |

---

## Flow 3: CMS Mock (Content Management System)

A CMS mock system with role-based content protection. Pages are accessed via short URLs with keys, and protection levels are enforced using Keycloak authentication and Step-up authentication (ACR).

### Protection Levels

| Level | Meaning | Behavior |
|-------|---------|----------|
| `public` | No auth required | Direct 302 redirect to content |
| `acr1` | Password login (LoA 1) | UserInfo check; if missing/invalid token or `acr < 1` → Keycloak redirect |
| `acr2` | MFA (LoA 2) | UserInfo check; if `acr < 2` → Keycloak Step-up redirect |

### Architecture

```
┌─────────────┐     ┌─────────────────┐     ┌────────────────────┐
│   Browser   │     │  auth-service   │     │     keycloak       │
│             │     │   (CMS Gateway)  │     │                    │
└──────┬──────┘     └────────┬────────┘     └─────────┬────────┘
       │                     │                          │
       │  GET /p/prm001-premium                        │
       │ ──────────────────► │                          │
       │                     │                          │
       │                     │  1. Lookup key in DB    │
       │                     │     (protection_level) │
       │                     │ ◄──────────────────────► │
       │                     │     (postgres)          │
       │                     │                          │
       │                     │  2. Check cms_session   │
       │                     │     cookie              │
       │                     │                          │
       │                     │  3. (If protected)      │
       │                     │     GET UserInfo        │
       │                     │ ◄───────────────────────► │
       │                     │                          │
       │                     │  4. Validate acr claim  │
       │                     │                          │
       │                     │  5. Redirect to         │
       │ ◄────────────────── │     /cms-content/*     │
       │                     │                          │
       │  GET /cms-content/premium.html                 │
       │ ─────────────────────────────────────────────►│◄── Caddy (static)
       │                     │                          │
       │                     │  6. Keycloak.js         │
       │                     │     check-sso           │
       │                     │ ◄───────────────────────► │
       │                     │                          │
```

### Full Auth Flow (12 Steps)

```
Step 1:  Browser GET https://cms.localhost:8443/p/prm001-premium

Step 2:  Caddy proxy /p/* → auth-service:8083

Step 3:  CmsController.resolve(key="prm001", name="premium")
         a) DB-Lookup: protection_level="acr1", content_path="/cms-content/premium.html"
         b) Cookie cms_session lesen (falls vorhanden)

Step 4:  protection_level == "public"
         → 302 Redirect zu https://cms.localhost:8443/cms-content/index.html
         (kein weiterer Check nötig)

Step 5:  protection_level == "acr1" oder "acr2", kein Cookie vorhanden
         → Weiter zu Step 6 (Keycloak-Redirect)

Step 6:  protection_level == "acr1" oder "acr2", Cookie vorhanden
         a) GET http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/userinfo
            Header: Authorization: Bearer {cms_session_cookie}
         b) Response 401 oder Fehler → Weiter zu Step 7 (Keycloak-Redirect)
         c) Prüfe acr-Claim aus UserInfo-Response:
            - Für acr1: acr-Wert >= 1 → 302 Redirect zu /cms-content/premium.html
            - Für acr2: acr-Wert >= 2 → 302 Redirect zu /cms-content/admin.html
            - acr zu niedrig → Weiter zu Step 7 (Step-up-Redirect)

Step 7:  302 Redirect zu Keycloak Authorization Endpoint
         Location: https://keycloak.localhost:8443/realms/auth-sandbox/protocol/openid-connect/auth
           ?client_id=cms-client
           &redirect_uri=https://cms.localhost:8443/cms/callback
           &response_type=code
           &scope=openid
           &acr_values={required_level}    ← "1" für acr1, "2" für acr2
           &state={Base64(return_url)}    ← z.B. Base64("/p/prm001-premium")

Step 8:  User authentifiziert sich bei Keycloak (Passwort, ggf. OTP für acr2)

Step 9:  Keycloak → GET https://cms.localhost:8443/cms/callback?code=...&state=...

Step 10: CmsController.callback(code, state)
         a) POST http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token
            Body: grant_type=authorization_code&client_id=cms-client&client_secret={secret}
                  &code={code}&redirect_uri=https://cms.localhost:8443/cms/callback
         b) Access Token aus Response extrahieren
         c) Cookie setzen: cms_session={access_token}; HttpOnly; Secure; Path=/; SameSite=Lax
         d) 302 Redirect zu {Base64-decode(state)} → /p/prm001-premium

Step 11: Browser GET /p/prm001-premium (jetzt mit Cookie)
         → Step 3–6: UserInfo OK, acr >= 1 → 302 zu /cms-content/premium.html

Step 12: Browser GET https://cms.localhost:8443/cms-content/premium.html
         → Caddy liefert statische HTML-Datei
         → Keycloak.js init({onLoad:'check-sso', clientId:'cms-premium-client'})
           → SSO-Session von Step 10 vorhanden → authenticated=true
```

### Keycloak Clients

| Client ID | Type | ACR | Redirect URI(s) | Usage |
|-----------|------|-----|-----------------|-------|
| `cms-client` | CONFIDENTIAL | — | `https://cms.localhost:8443/cms/callback` | Server-side code exchange + token userinfo |
| `cms-public-client` | PUBLIC | 0 | `https://cms.localhost:8443/cms-content/index.html` | Keycloak.js check-sso on public.html |
| `cms-premium-client` | PUBLIC | 1 | `https://cms.localhost:8443/cms-content/premium.html` | Keycloak.js check-sso on premium.html |
| `cms-admin-client` | PUBLIC | 2 | `https://cms.localhost:8443/cms-content/admin.html` | Keycloak.js check-sso on admin.html |

### Database Model

```sql
CREATE TABLE device_login.cms_pages (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL,
    key              VARCHAR(8)   NOT NULL UNIQUE,
    protection_level VARCHAR(20)  NOT NULL DEFAULT 'public',
    content_path     VARCHAR(255) NOT NULL,
    created_at       TIMESTAMP    DEFAULT NOW()
);
```

### CMS Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/p/{key}-{name}` | Check protection level → 302 to content or Keycloak |
| GET | `/cms/callback` | OAuth2 callback → code exchange → cookie set → 302 |
| POST | `/api/v1/cms/pages` | Create page (HTTP Basic Auth) |
| GET | `/api/v1/cms/pages` | List all pages (HTTP Basic Auth) |
| DELETE | `/api/v1/cms/pages/{id}` | Delete page (HTTP Basic Auth) |

---

# Repository Structure

| Directory / File | Purpose |
|------------------|---------|
| `auth-service/` | Spring Boot 3 / Java 21 — merged backend (device-login + sso-proxy + CMS) |
| `keycloak-extension/` | Keycloak SPI (`LoginTokenAuthenticator`) |
| `app-mock-react/` | React/TS/Vite/Tailwind — mobile app mock |
| `admin-mock-react/` | React/TS/Vite/Tailwind — admin panel |
| `target-app-react/` | React/TS/Vite/Tailwind — OIDC Auth Code + PKCE target SPA |
| `cms-admin-react/` | React/TS/Vite/Tailwind — CMS admin panel |
| `cms-content/` | Static HTML content pages |
| `c4-spec/` | LikeC4 architecture diagrams |
| `tofu/` | OpenTofu (Terraform) — Keycloak realm setup |
| `compose.yml` | Podman Compose stack (9 services) |
| `Caddyfile` | Caddy reverse proxy — TLS termination for `*.localhost` |
| `.env` / `.env.example` | Secrets — `.env` is **not committed** |

---

# Quick Start

## Prerequisites

```bash
brew install podman podman-compose
```

## Setup

```bash
# 1. /etc/hosts (one-time)
echo "127.0.0.1  keycloak.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  auth-service.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  app-mock.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  admin.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  target-app.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  home.localhost" | sudo tee -a /etc/hosts
echo "127.0.0.1  cms.localhost" | sudo tee -a /etc/hosts

# 2. Copy and edit .env
cp .env.example .env

# 3. Generate RSA keys
mkdir -p auth-service/keys
openssl genrsa -out auth-service/keys/private.pem 4096
openssl rsa -in auth-service/keys/private.pem -pubout -out auth-service/keys/public.pem

# 4. Build Keycloak extension
cd keycloak-extension && ./gradlew jar && cd ..

# 5. Start stack
podman compose up -d

# 6. Configure Keycloak realm (see SETUP.md)
```

---

# Access URLs

| URL | Description |
|-----|-------------|
| https://home.localhost:8443 | Developer start page |
| https://keycloak.localhost:8443 | Keycloak Admin UI |
| https://auth-service.localhost:8443/actuator/health | auth-service health check |
| https://admin.localhost:8443 | Admin mock panel |
| https://app-mock.localhost:8443 | Mobile app mock |
| https://target-app.localhost:8443 | SSO transfer target app |
| https://cms.localhost:8443 | CMS content pages |
| https://cms.localhost:8443/cms-admin/ | CMS admin panel |

---

# Build & Test Commands

## React Apps

```bash
cd app-mock-react && npm run build
cd admin-mock-react && npm run build
cd target-app-react && npm run build
cd cms-admin-react && npm run build
```

## React Tests (Playwright)

```bash
cd app-mock-react && npm test
cd admin-mock-react && npm test
cd target-app-react && npm test
cd cms-admin-react && npm test
```

## auth-service

```bash
cd auth-service && ./gradlew bootJar
cd auth-service && ./gradlew test
cd auth-service && ./gradlew test --tests "dev.authsandbox.authservice.service.JwtServiceTest"
```

## keycloak-extension

```bash
cd keycloak-extension && ./gradlew jar
```

---

# Security Conventions

- Never log secrets, tokens, passwords, or private keys
- Never commit secrets — use environment variables
- Cryptographic operations must use well-vetted libraries; no custom crypto primitives
- JWT validation must always verify signature, `exp`, `aud`, and `iss`
- Device tokens are sensitive; treat them with the same care as OIDC access tokens
