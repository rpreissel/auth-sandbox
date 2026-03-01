# Project

* Runs on Podman Compose (`compose.yml`)
* Services: postgres, keycloak, auth-service, app-mock, admin-mock, target-app, home, caddy
* See `AGENTS.md` for setup instructions and architecture details


# Nächste Aufgaben

## Content Management System Mock
Ich möchte ein Content Management System, CMS, ähnlich wie Coremedia, als kleinen Mock erzeugen. 
Unter verschiedenen URLs kann man Webseiten registrieren und festlegen, welcher Schutzlevel notwendig ist. 
Das System generiert dann Links, die aus Schlüsseln bestehen, plus eine kurze Bezeichnung der Webseite.

---

## CMS-Mock Plan

### Architektur

| Komponente | Beschreibung |
|------------|---------------|
| **auth-service** | Auth-Gateway: prüft Schutzlevel, macht Introspection, leitet zu Keycloak oder Content weiter |
| **Datenbank** | Tabelle `cms_pages` im Schema `device_login` (Flyway V10) |
| **Keycloak** | 4 Clients: `cms-client` (CONFIDENTIAL, Code-Exchange) + 3 PUBLIC-Clients für Keycloak.js |
| **Content** | Statische HTML-Dateien unter `./cms-content/` (→ `/srv/cms-content/` via Caddy) |
| **cms-admin-react** | Neue React-SPA für CMS-Verwaltung (analog zu `admin-mock-react`) |
| **Caddy** | Neuer vhost `cms.localhost:443`: `/p/*`, `/cms/*`, `/api/*` → auth-service; `/cms-admin/*` → SPA; `/cms-content/*` → statisch |

### Keycloak-Clients

| Client ID | Typ | ACR | Redirect URI(s) | Verwendung |
|-----------|-----|-----|-----------------|------------|
| `cms-client` | CONFIDENTIAL | — | `https://cms.localhost:8443/cms/callback` | Server-seitiger Code-Exchange + Token-Introspection |
| `cms-public-client` | PUBLIC | 0 | `https://cms.localhost:8443/cms-content/index.html` | Keycloak.js check-sso auf public.html |
| `cms-premium-client` | PUBLIC | 1 | `https://cms.localhost:8443/cms-content/premium.html` | Keycloak.js check-sso auf premium.html |
| `cms-admin-client` | PUBLIC | 2 | `https://cms.localhost:8443/cms-content/admin.html` | Keycloak.js check-sso auf admin.html |

**Wichtig:** Die drei PUBLIC-Clients (`cms-public-client`, `cms-premium-client`, `cms-admin-client`) machen **keinen** server-seitigen Code-Exchange. Sie sind ausschließlich für den Keycloak.js-`check-sso`-Call auf den statischen Content-Seiten. Der eigentliche Auth-Flow (Login, Code-Exchange, Cookie setzen) läuft immer über `cms-client`.

### Datenmodell

Flyway-Migration `V10__add_cms_pages.sql` im Schema `device_login`:

```sql
CREATE TABLE device_login.cms_pages (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL,                          -- "public", "premium", "admin"
    key              VARCHAR(8)   NOT NULL UNIQUE,                   -- "pub001", "prm001", "adm001"
    protection_level VARCHAR(20)  NOT NULL DEFAULT 'public',         -- "public", "acr1", "acr2"
    content_path     VARCHAR(255) NOT NULL,                          -- "/cms-content/index.html"
    created_at       TIMESTAMP    DEFAULT NOW()
);

-- Seed-Daten: 3 Standard-Seiten
INSERT INTO device_login.cms_pages (name, key, protection_level, content_path) VALUES
    ('public',  'pub001', 'public', '/cms-content/index.html'),
    ('premium', 'prm001', 'acr1',   '/cms-content/premium.html'),
    ('admin',   'adm001', 'acr2',   '/cms-content/admin.html');
```

### Schutzlevel

| Level | Bedeutung | Verhalten |
|-------|-----------|-----------|
| `public` | Keine Auth erforderlich | Direkter 302-Redirect zu `content_path` |
| `acr1` | Passwort-Login (LoA 1) | Introspection; bei fehlendem/ungültigem Token oder `acr < 1` → Keycloak-Redirect |
| `acr2` | MFA (LoA 2) | Introspection; bei `acr < 2` → Keycloak Step-up-Redirect |

**ACR-Vergleich:** Der numerische Wert aus dem Token-`acr`-Claim wird mit dem erforderlichen Level verglichen. Ein `acr2`-Token ist auch gültig für `acr1`-Seiten.

**Cookie-Strategie:** Ein einziger Cookie `cms_session` (HttpOnly, Secure, Path=/) enthält den Keycloak Access Token. Bei Step-up wird der Cookie mit dem neuen (höher gestuften) Token überschrieben.

### Endpunkte

| Methode | Pfad | Beschreibung |
|---------|------|--------------|
| `GET` | `/p/{key}-{name}` | Schutzlevel prüfen → 302 zu Content oder Keycloak |
| `GET` | `/cms/callback` | Keycloak OAuth2 Callback → Code tauschen → Cookie setzen → 302 |
| `POST` | `/api/v1/cms/pages` | Seite erstellen (HTTP Basic Auth) |
| `GET` | `/api/v1/cms/pages` | Alle Seiten auflisten (HTTP Basic Auth) |
| `DELETE` | `/api/v1/cms/pages/{id}` | Seite löschen (HTTP Basic Auth) |

**Hinweis:** Es gibt keinen serverseitigen `/cms/admin`-Endpoint mehr. Die Admin-UI ist eine eigenständige React-SPA unter `cms-admin-react/`, ausgeliefert auf `https://cms.localhost:8443/cms-admin/`.

### Auth-Flow (vollständig)

```
Schritt 1: Browser GET https://cms.localhost:8443/p/prm001-premium

Schritt 2: Caddy proxy /p/* → auth-service:8083

Schritt 3: CmsController.resolve(key="prm001", name="premium")
   a) DB-Lookup: protection_level="acr1", content_path="/cms-content/premium.html"
   b) Cookie cms_session lesen (falls vorhanden)

Schritt 4: protection_level == "public"
   → 302 Redirect zu https://cms.localhost:8443/cms-content/index.html
   (kein weiterer Check nötig)

Schritt 5: protection_level == "acr1" oder "acr2", kein Cookie vorhanden
   → Weiter zu Schritt 6 (Keycloak-Redirect)

Schritt 6: protection_level == "acr1" oder "acr2", Cookie vorhanden
   a) POST http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token/introspect
      Body: token={cms_session_cookie}&client_id=cms-client&client_secret={secret}
   b) Response active=false oder Fehler → Weiter zu Schritt 7 (Keycloak-Redirect)
   c) Prüfe acr-Claim aus Introspection-Response:
      - Für acr1: acr-Wert >= 1 → 302 Redirect zu /cms-content/premium.html ✓
      - Für acr2: acr-Wert >= 2 → 302 Redirect zu /cms-content/admin.html ✓
      - acr zu niedrig → Weiter zu Schritt 7 (Step-up-Redirect)

Schritt 7: 302 Redirect zu Keycloak Authorization Endpoint
   Location: https://keycloak.localhost:8443/realms/auth-sandbox/protocol/openid-connect/auth
     ?client_id=cms-client
     &redirect_uri=https://cms.localhost:8443/cms/callback
     &response_type=code
     &scope=openid
     &acr_values={erforderlicher_level}    ← "1" für acr1, "2" für acr2
     &state={Base64(return_url)}           ← z.B. Base64("/p/prm001-premium")

Schritt 8: User authentifiziert sich bei Keycloak (Passwort, ggf. OTP für acr2)

Schritt 9: Keycloak → GET https://cms.localhost:8443/cms/callback?code=...&state=...

Schritt 10: CmsController.callback(code, state)
   a) POST http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token
      Body: grant_type=authorization_code&client_id=cms-client&client_secret={secret}
            &code={code}&redirect_uri=https://cms.localhost:8443/cms/callback
   b) Access Token aus Response extrahieren
   c) Cookie setzen: cms_session={access_token}; HttpOnly; Secure; Path=/; SameSite=Lax
   d) 302 Redirect zu {Base64-decode(state)} → /p/prm001-premium

Schritt 11: Browser GET /p/prm001-premium (jetzt mit Cookie)
   → Schritt 3–6: Introspection OK, acr >= 1 → 302 zu /cms-content/premium.html

Schritt 12: Browser GET https://cms.localhost:8443/cms-content/premium.html
   → Caddy liefert statische HTML-Datei aus /srv/cms-content/premium.html
   → Keycloak.js init({onLoad:'check-sso', clientId:'cms-premium-client'})
     → SSO-Session von Schritt 10 vorhanden → authenticated=true
     → Zeigt preferred_username und acr-Claim
```

**Hinweis:** Introspection wird bei **jedem** `/p/**`-Request durchgeführt (kein lokales Token-Caching), um revoked/expired Tokens sofort zu erkennen.

### Content-Seiten (`./cms-content/`)

Drei statische HTML-Dateien, ausgeliefert von Caddy unter `/srv/cms-content/`:

| Datei | Keycloak Client | Schutzlevel |
|-------|-----------------|-------------|
| `index.html` | `cms-public-client` | public |
| `premium.html` | `cms-premium-client` | acr1 |
| `admin.html` | `cms-admin-client` | acr2 |

Jede Seite lädt Keycloak.js von Keycloak (`https://keycloak.localhost:8443/js/keycloak.js`) und macht einen `check-sso`-Init. Da der Browser nach dem Auth-Flow eine Keycloak-SSO-Session hat (gesetzt durch den Cookie-Exchange in Schritt 10), wird `check-sso` direkt `authenticated=true` zurückgeben.

Struktur jeder Content-Seite:
```html
<!DOCTYPE html>
<html>
<head><title>CMS — {Titel}</title></head>
<body>
  <h1>{Titel}</h1>
  <p>Schutzlevel: {level}</p>
  <p>User: <span id="user">Lädt...</span></p>
  <p>ACR: <span id="acr">Lädt...</span></p>

  <script src="https://keycloak.localhost:8443/js/keycloak.js"></script>
  <script>
    const keycloak = new Keycloak({
      url: 'https://keycloak.localhost:8443',
      realm: 'auth-sandbox',
      clientId: '{cms-public-client | cms-premium-client | cms-admin-client}'
    });
    keycloak.init({
      onLoad: 'check-sso',
      silentCheckSsoRedirectUri: window.location.origin + '/cms-content/silent-check-sso.html'
    }).then(authenticated => {
      document.getElementById('user').textContent =
        authenticated ? keycloak.tokenParsed.preferred_username : 'Nicht eingeloggt';
      document.getElementById('acr').textContent =
        authenticated ? keycloak.tokenParsed.acr : '-';
    });
  </script>

  <nav>
    <a href="/p/pub001-public">Public</a> |
    <a href="/p/prm001-premium">Premium</a> |
    <a href="/p/adm001-admin">Admin</a>
  </nav>
</body>
</html>
```

Zusätzlich wird `./cms-content/silent-check-sso.html` benötigt (leere Seite für den Silent-SSO-Iframe von Keycloak.js).

### Admin-SPA (`cms-admin-react/`)

Neue React-SPA analog zu `admin-mock-react` (Vite + TypeScript + Tailwind CSS + Playwright).

**URL:** `https://cms.localhost:8443/cms-admin/`

**Funktion:**
- Login-Overlay mit HTTP Basic Auth (gleiche Credentials wie bestehende Admin-UI: `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`)
- Tabelle aller CMS-Pages mit Spalten: Name, Key, Protection Level, Content Path, Aktionen
- Formular zum Erstellen einer neuen Seite (Name, Key, Protection Level, Content Path)
- Schaltfläche zum Löschen einer Seite

**API-Calls:** Direkt gegen `https://cms.localhost:8443/api/v1/cms/pages` (same-origin via Caddy).

**Build:** `npm run build` → `cms-admin-react/dist/` → Caddy mountet als `/srv/cms-admin`.

### Backend — auth-service Erweiterungen

#### Neue Datei: `config/KeycloakCmsProperties.java`

```java
@ConfigurationProperties(prefix = "app.keycloak.cms")
public record KeycloakCmsProperties(
    String clientId,           // cms-client
    String clientSecret,       // aus Env
    String callbackUri,        // https://cms.localhost:8443/cms/callback
    String introspectEndpoint, // http://keycloak:8080/realms/auth-sandbox/.../introspect
    String authPublicEndpoint, // https://keycloak.localhost:8443/realms/auth-sandbox/.../auth
    String tokenEndpoint       // http://keycloak:8080/realms/auth-sandbox/.../token
) {}
```

#### Neue Datei: `entity/CmsPage.java`

JPA-Entity für Tabelle `device_login.cms_pages`. Felder: `id` (UUID), `name`, `key`, `protectionLevel`, `contentPath`, `createdAt`.

#### Neue Datei: `repository/CmsPageRepository.java`

Spring Data JPA Repository mit Methode `Optional<CmsPage> findByKey(String key)`.

#### Neue Dateien: DTOs

- `dto/CmsPageRequest.java`: Record mit `name`, `key`, `protectionLevel`, `contentPath` (alle mit `@NotBlank`)
- `dto/CmsPageResponse.java`: Record mit `id`, `name`, `key`, `protectionLevel`, `contentPath`, `createdAt`

#### Neue Datei: `service/CmsService.java`

```
resolveAccess(key, name, sessionToken):
  1. DB-Lookup findByKey(key) → 404 wenn nicht gefunden
  2. protection_level == "public" → return content_path (kein Token nötig)
  3. sessionToken == null → return buildKeycloakAuthUrl(protection_level, return_url)
  4. Introspection: POST introspect endpoint mit cms-client credentials
  5. active == false → return buildKeycloakAuthUrl(protection_level, return_url)
  6. acr = parseInt(introspect_response["acr"])
     required = protection_level == "acr1" ? 1 : 2
  7. acr >= required → return content_path
  8. acr < required → return buildKeycloakAuthUrl(required_acr, return_url)

buildKeycloakAuthUrl(acrLevel, returnUrl):
  → authPublicEndpoint + query params:
    client_id=cms-client, redirect_uri=callbackUri,
    response_type=code, scope=openid,
    acr_values={acrLevel}, state=Base64(returnUrl)

callback(code, state):
  1. POST tokenEndpoint: grant_type=authorization_code, client_id, client_secret, code, redirect_uri
  2. access_token aus Response extrahieren
  3. return (access_token, Base64-decode(state))

CRUD:
  createPage(request) → CmsPage speichern
  listPages() → alle CmsPage-Einträge
  deletePage(id) → löschen oder 404
```

#### Neue Datei: `controller/CmsController.java`

```
@RestController
GET  /p/{key}-{name}
  → Liest Cookie cms_session aus HttpServletRequest
  → CmsService.resolveAccess(key, name, token)
  → Ergebnis ist URL: wenn starts_with("/cms-content") → 302 Redirect
  → Ergebnis ist Keycloak-URL → 302 Redirect

GET  /cms/callback?code=...&state=...
  → CmsService.callback(code, state) → (access_token, returnUrl)
  → Setzt Cookie: cms_session={access_token}; HttpOnly; Secure; Path=/; SameSite=Lax; Max-Age=3600
  → 302 Redirect zu returnUrl

POST /api/v1/cms/pages        (gesichert via SecurityConfig HTTP Basic)
GET  /api/v1/cms/pages        (gesichert via SecurityConfig HTTP Basic)
DELETE /api/v1/cms/pages/{id} (gesichert via SecurityConfig HTTP Basic)
```

#### Änderung: `config/SecurityConfig.java`

Den Pfad `/api/v1/cms/pages/**` dem bestehenden `ADMIN`-Rolle-Schutz hinzufügen (analog zu `/api/v1/admin/**`).

Zusätzlich: `/p/**` und `/cms/**` als permitAll konfigurieren (kein Spring Security Auth — Auth läuft manuell im Controller über Cookie + Keycloak Introspection).

#### Änderung: `application.yaml`

```yaml
app:
  keycloak:
    cms:
      client-id: ${KEYCLOAK_CMS_CLIENT_ID:cms-client}
      client-secret: ${KEYCLOAK_CMS_CLIENT_SECRET}
      callback-uri: ${KEYCLOAK_CMS_CALLBACK_URI:https://cms.localhost:8443/cms/callback}
      introspect-endpoint: ${KEYCLOAK_CMS_INTROSPECT_ENDPOINT:http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token/introspect}
      auth-public-endpoint: ${KEYCLOAK_CMS_AUTH_PUBLIC_ENDPOINT:https://keycloak.localhost:8443/realms/auth-sandbox/protocol/openid-connect/auth}
      token-endpoint: ${KEYCLOAK_CMS_TOKEN_ENDPOINT:http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token}
```

### OpenTofu — `tofu/realm.tf` Ergänzungen

Vier neue Clients:

```hcl
# cms-client (CONFIDENTIAL) — server-seitiger Code-Exchange + Introspection
resource "keycloak_openid_client" "cms_client" {
  realm_id    = keycloak_realm.auth_sandbox.id
  client_id   = "cms-client"
  access_type = "CONFIDENTIAL"
  standard_flow_enabled = true
  valid_redirect_uris   = [var.cms_callback_uri]
  client_secret         = var.cms_client_secret
}

# cms-public-client (PUBLIC) — Keycloak.js check-sso auf index.html
resource "keycloak_openid_client" "cms_public_client" {
  realm_id    = keycloak_realm.auth_sandbox.id
  client_id   = "cms-public-client"
  access_type = "PUBLIC"
  standard_flow_enabled = true
  valid_redirect_uris   = [var.cms_public_redirect_uri]
}

# cms-premium-client (PUBLIC) — Keycloak.js check-sso auf premium.html
resource "keycloak_openid_client" "cms_premium_client" {
  realm_id    = keycloak_realm.auth_sandbox.id
  client_id   = "cms-premium-client"
  access_type = "PUBLIC"
  standard_flow_enabled = true
  valid_redirect_uris   = [var.cms_premium_redirect_uri]
}

# cms-admin-client (PUBLIC) — Keycloak.js check-sso auf admin.html
resource "keycloak_openid_client" "cms_admin_client" {
  realm_id    = keycloak_realm.auth_sandbox.id
  client_id   = "cms-admin-client"
  access_type = "PUBLIC"
  standard_flow_enabled = true
  valid_redirect_uris   = [var.cms_admin_redirect_uri]
}
```

Neue Variablen in `variables.tf`:
- `cms_client_secret` (sensitive)
- `cms_callback_uri` (default: `https://cms.localhost:8443/cms/callback`)
- `cms_public_redirect_uri` (default: `https://cms.localhost:8443/cms-content/index.html`)
- `cms_premium_redirect_uri` (default: `https://cms.localhost:8443/cms-content/premium.html`)
- `cms_admin_redirect_uri` (default: `https://cms.localhost:8443/cms-content/admin.html`)

### Caddy-Konfiguration (`Caddyfile`)

Neuer vhost am Ende der Datei:

```caddyfile
# cms.localhost — CMS Mock
cms.localhost:443 {
    tls internal

    # /p/* — Auth-Gateway (Schutzlevel-Check + Redirect)
    handle /p/* {
        reverse_proxy auth-service:8083 {
            header_up X-Forwarded-Proto "https"
            header_up X-Forwarded-Port  "8443"
        }
    }

    # /cms/* — OAuth2 Callback
    handle /cms/* {
        reverse_proxy auth-service:8083 {
            header_up X-Forwarded-Proto "https"
            header_up X-Forwarded-Port  "8443"
        }
    }

    # /api/* — CMS Admin-API (HTTP Basic Auth)
    handle /api/* {
        reverse_proxy auth-service:8083 {
            header_up X-Forwarded-Proto "https"
            header_up X-Forwarded-Port  "8443"
        }
    }

    # /cms-admin/* — React Admin-SPA (SPA-Fallback)
    handle /cms-admin/* {
        uri strip_prefix /cms-admin
        root * /srv/cms-admin
        try_files {path} /index.html
        file_server
    }

    # /cms-content/* — Statische Content-Seiten
    handle /cms-content/* {
        root * /srv
        file_server
    }
}
```

### Compose-Änderungen (`compose.yml`)

**auth-service** — neue Env-Variablen:
```yaml
KEYCLOAK_CMS_CLIENT_ID: ${KEYCLOAK_CMS_CLIENT_ID}
KEYCLOAK_CMS_CLIENT_SECRET: ${KEYCLOAK_CMS_CLIENT_SECRET}
KEYCLOAK_CMS_CALLBACK_URI: ${KEYCLOAK_CMS_CALLBACK_URI:-https://cms.localhost:8443/cms/callback}
KEYCLOAK_CMS_INTROSPECT_ENDPOINT: ${KEYCLOAK_CMS_INTROSPECT_ENDPOINT:-http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token/introspect}
KEYCLOAK_CMS_AUTH_PUBLIC_ENDPOINT: ${KEYCLOAK_CMS_AUTH_PUBLIC_ENDPOINT:-https://keycloak.localhost:8443/realms/auth-sandbox/protocol/openid-connect/auth}
KEYCLOAK_CMS_TOKEN_ENDPOINT: ${KEYCLOAK_CMS_TOKEN_ENDPOINT:-http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token}
```

**caddy** — neue Volumes:
```yaml
- ./cms-content:/srv/cms-content:ro
- ./cms-admin-react/dist:/srv/cms-admin:ro
```

### Environment-Variablen (`.env.example`)

Neue Einträge:
```dotenv
# CMS Mock — cms-client (CONFIDENTIAL, server-seitiger Code-Exchange)
KEYCLOAK_CMS_CLIENT_ID=cms-client
KEYCLOAK_CMS_CLIENT_SECRET=change-me-cms-client-secret
KEYCLOAK_CMS_CALLBACK_URI=https://cms.localhost:8443/cms/callback
KEYCLOAK_CMS_INTROSPECT_ENDPOINT=http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token/introspect
KEYCLOAK_CMS_AUTH_PUBLIC_ENDPOINT=https://keycloak.localhost:8443/realms/auth-sandbox/protocol/openid-connect/auth
KEYCLOAK_CMS_TOKEN_ENDPOINT=http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token
```

### Tests — `CmsServiceTest.java`

Unit-Tests mit Mockito (analog zu bestehenden Service-Tests):

| Test | Erwartetes Verhalten |
|------|----------------------|
| `resolveAccess_public_returnsContentPath` | `protection_level=public` → direkt `/cms-content/index.html`, kein Introspection-Call |
| `resolveAccess_acr1_noToken_returnsKeycloakUrl` | Kein Cookie → Keycloak-URL mit `acr_values=1` |
| `resolveAccess_acr1_activeToken_acrSufficient_returnsContentPath` | Token aktiv, `acr=1` → `/cms-content/premium.html` |
| `resolveAccess_acr1_activeToken_acrTooLow_returnsKeycloakUrl` | Token aktiv, `acr=0` → Keycloak-URL mit `acr_values=1` |
| `resolveAccess_acr2_activeToken_acr1_returnsStepUpUrl` | Token aktiv, `acr=1`, required=2 → Keycloak-URL mit `acr_values=2` |
| `resolveAccess_acr2_activeToken_acr2_returnsContentPath` | Token aktiv, `acr=2` → `/cms-content/admin.html` |
| `resolveAccess_inactiveToken_returnsKeycloakUrl` | Token `active=false` → Keycloak-URL |
| `resolveAccess_unknownKey_throwsNotFoundException` | Key nicht in DB → `ResponseStatusException(404)` |
| `callback_exchangesCodeAndReturnsTokenAndUrl` | Code-Exchange erfolgreich → access_token + decoded returnUrl |

### Implementierungsreihenfolge

1. **OpenTofu** (`tofu/`): 4 neue Clients in `realm.tf`, neue Variablen in `variables.tf` und `terraform.tfvars.example`
2. **Environment** (`.env.example`, `compose.yml`): neue CMS-Variablen
3. **Flyway V10** (`auth-service/src/main/resources/db/migration/V10__add_cms_pages.sql`): Tabelle + Seed-Daten
4. **Backend** (`auth-service/`):
   - `KeycloakCmsProperties.java`
   - `CmsPage.java` (Entity)
   - `CmsPageRepository.java`
   - `CmsPageRequest.java`, `CmsPageResponse.java` (DTOs)
   - `CmsService.java`
   - `CmsController.java`
   - `SecurityConfig.java` anpassen
   - `application.yaml` ergänzen
   - `AppConfig.java`: `@EnableConfigurationProperties` um `KeycloakCmsProperties` erweitern
5. **Content-Seiten** (`./cms-content/`): `index.html`, `premium.html`, `admin.html`, `silent-check-sso.html`
6. **Admin-SPA** (`cms-admin-react/`): Vite-Projekt erstellen, Login-Overlay + Tabelle + Formular
7. **Caddy** (`Caddyfile`): neuen vhost `cms.localhost:443` hinzufügen
8. **Compose** (`compose.yml`): Env-Variablen + Volumes ergänzen
9. **SETUP.md**: `cms.localhost` in `/etc/hosts`-Block ergänzen
10. **Tests** (`auth-service/src/test/`): `CmsServiceTest.java`
