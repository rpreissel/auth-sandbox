# Credential-basierter Login-Flow (Umbau)

## Übersicht

Der Login-Flow wird von einem IDP/JWKS-basierten JWT-Mechanismus auf einen **Keycloak Credential-basierten Flow** umgebaut.

**Ziele:**
- Signaturprüfung vollständig in Keycloak (nicht mehr im Auth Service)
- User kann das Credential über die Keycloak Account Console löschen
- JWKS-Endpoint für den Login Flow entfällt
- **Generalisierter login_token mit type-Feld** ("device" oder "sso")
- **Separate Authenticator-Implementierungen** pro Typ
- **Conditional Authentication** im Keycloak Flow

---

## Architektur

### Vorher (JWT-basiert)

```
App → Auth Service: deviceId
Auth Service → Challenge (mit challengeValue)
App → sign(challengeValue) → Auth Service
Auth Service → JWT (issueKeycloakAssertionToken) → Keycloak
Keycloak → verify JWT via JWKS + IDP FederatedIdentity
```

### Nachher (Credential-basiert)

```
┌─────────────────────────────────────────────────────────────────────┐
│ REGISTRIERUNG                                                        │
├─────────────────────────────────────────────────────────────────────┤
│ App → Auth Service: userId, deviceName, activationCode, publicKey   │
│       (deviceName = Geraetename, z.B. "Pixel 8")                    │
│                                                                      │
│ Auth Service:                                                        │
│   1. RegistrationCode validieren                                     │
│   2. Pruefe deviceName-Eindeutigkeit pro User                        │
│   3. publicKeyHash = SHA-256(publicKey) hex                          │
│   4. RSA-2048 Keypair erzeugen → (encPrivKey, encPubKey)             │
│   5. Keycloak Credential anlegen:                                    │
│        userLabel: deviceName (sichtbar in Keycloak Account Console!) │
│        credentialData: { publicKey, publicKeyHash }                  │
│        secretData: { encPrivKey }                                    │
│   6. Device speichern: deviceName + publicKeyHash + encPubKey        │
│   7. Response: "Device registered successfully", deviceName          │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ LOGIN Schritt 1: startLogin                                          │
├─────────────────────────────────────────────────────────────────────┤
│ App → { publicKeyHash }                                              │
│                                                                      │
│ Auth Service:                                                        │
│   1. Device lookup via publicKeyHash                                 │
│   2. encPubKey DIREKT AUS DB lesen (kein Admin-API-Call!)            │
│   3. payload = JSON { userId, nonce, exp }                           │
│   4. Hybrid-Encrypt:                                                 │
│        - AES-GCM(payload) → encryptedData                            │
│        - RSA-OAEP(aesKey, encPubKey) → encryptedKey                  │
│   5. nonce + exp + userId in Challenge-Tabelle (used=false)          │
│                                                                      │
│ Response: { encryptedKey, encryptedData, iv, nonce }                 │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ LOGIN Schritt 2: verifyChallenge                                     │
├─────────────────────────────────────────────────────────────────────┤
│ App → { nonce, encryptedKey, encryptedData, iv, signature }          │
│       signature = SHA256withRSA(encryptedData)                       │
│                                                                      │
│ Auth Service:                                                        │
│   1. Challenge lookup via nonce                                      │
│   2. used=false + exp prüfen                                         │
│   3. used=true markieren                                             │
│   4. login_token = Base64-JSON {                                     │
│        type: "device",        -- Typ-Angabe                          │
│        sub: userId,                                                  │
│        encryptedKey,                                                 │
│        encryptedData,                                                │
│        iv,                                                           │
│        signature                                                     │
│      }                                                               │
│   5. Auth Code Flow → Keycloak ?login_token=...                      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ Keycloak: Authentication Flow (Conditional)                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Flow: Conditional Authenticator                                     │
│  ├── DeviceLoginConditionAuthenticator (CONDITIONAL)                 │
│  │       └── Prueft: login_token.type == "device"                    │
│  │                                                                   │
│  ├── DeviceLoginTokenAuthenticator (REQUIRED)                        │
│  │       └── Credential-basierter Login                              │
│  │                                                                   │
│  ├── SsoLoginConditionAuthenticator (CONDITIONAL)                    │
│  │       └── Prueft: login_token.type == "sso"                       │
│  │                                                                   │
│  └── SsoLoginTokenAuthenticator (REQUIRED)                           │
│          └── SSO-Transfer Login                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Datenmodell

### Flyway V11

```sql
-- devices: device_id entfernen, neue Spalten hinzufuegen
ALTER TABLE device_login.devices DROP COLUMN IF EXISTS device_id;
ALTER TABLE device_login.devices ADD COLUMN public_key_hash VARCHAR(64);
ALTER TABLE device_login.devices ADD COLUMN enc_pub_key TEXT NOT NULL DEFAULT '';
ALTER TABLE device_login.devices ADD COLUMN device_name VARCHAR(255) NOT NULL DEFAULT '';

-- V4 hat UNIQUE INDEX auf user_id gesetzt – muss weg, da ein User mehrere Devices haben kann
DROP INDEX IF EXISTS device_login.idx_devices_user_id;

CREATE UNIQUE INDEX idx_devices_public_key_hash ON device_login.devices(public_key_hash);
CREATE UNIQUE INDEX idx_devices_user_name ON device_login.devices(user_id, device_name);

-- challenges: device_id entfernen, user_id hinzufuegen, challenge_value nullable
ALTER TABLE device_login.challenges DROP COLUMN IF EXISTS device_id;
ALTER TABLE device_login.challenges ADD COLUMN user_id VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE device_login.challenges ALTER COLUMN challenge_value DROP NOT NULL;
```

### Datenverteilung (Performance-Optimiert!)

| Daten | Speicherort | Warum |
|-------|-------------|-------|
| `deviceName` | DB + Keycloak userLabel | **Vom User vergeben** (z.B. "Pixel 8"), in Keycloak Account Console sichtbar |
| `publicKeyHash` | DB | Device-Lookup in startLogin |
| `encPubKey` | DB | **Direkt aus DB lesen** — kein Admin-API-Call! |
| `encPrivKey` | Keycloak Credential (secretData) | Nur für Keycloak-Validierung |
| `publicKey` | Keycloak Credential (credentialData) | Signaturprüfung in Keycloak |
| `userId` | DB (devices.user_id) + Challenge | Token-Erstellung |
| `nonce` | Challenge | Global eindeutige Korrelations-ID |

### Eindeutigkeit

- `publicKeyHash`: global eindeutig (pro Device)
- `deviceName`: **eindeutig pro User** (User kann mehrere Devices haben, z.B. "Pixel 8", "MacBook Pro")
- `userId` auf `devices`: **nicht unique** — ein User kann mehrere Devices registrieren
- `nonce`: global eindeutig (pro Challenge)

### Keycloak Credential (device-login)

**credentialData (JSON-String):**
```json
{
  "publicKey": "-----BEGIN PUBLIC KEY-----...",
  "publicKeyHash": "a1b2c3d4..."
}
```

**secretData (JSON-String):**
```json
{
  "encPrivKey": "BASE64_PKCS8"
}
```

> **Hinweis:** `encPubKey` liegt **nicht** im Keycloak-Credential, sondern in `devices.enc_pub_key` (DB).
> Das ermöglicht den direkten DB-Zugriff in `startLogin` ohne Admin-API-Call.

---

## Was entfällt

- `challengeValue` (DB-Spalte + Logik überall)
- JWKS-Endpoint für Login Flow
- `issueKeycloakAssertionToken()` in JwtService
- IDP `device-login-idp` in Keycloak
- `ensureDeviceLoginFederatedIdentityLink()`
- FederatedIdentity-Links für device-login
- `CachedJWKSet`, JWKS-Fetch, `trusted-client-ids`
- `findIdpByIssuer()`, `getClientJwksUrl()`, `isTrustedClient()`

### Was bleibt

- JwtService für SSO Transfer Flow (JWKS-Endpoint in TransferController)
- `issueTransferToken()`, `issueStateToken()`, `validateTransferToken()`, `validateStateToken()`
- `ensureSsoProxyFederatedIdentityLink()` (SSO Transfer Flow)

---

## Beads (Issues)

**Hinweis:** `deviceId` ist komplett entfernt. Jedes Device hat stattdessen einen `deviceName` (vom User vergeben, z.B. "Pixel 8"), der pro User eindeutig sein muss.

| # | ID | Titel | Typ | Priorität | Abhängigkeiten |
|---|-----|-------|-----|-----------|----------------|
| 1 | auth-sandbox-6ye | Flyway V11: + deviceName (eindeutig pro User) | task | P2 | - |
| 2 | auth-sandbox-956 | Device Entity + DeviceRepository: + deviceName | task | P2 | 6ye |
| 3 | auth-sandbox-s22 | Challenge Entity: OHNE deviceId, + userId | task | P2 | 6ye |
| 4 | auth-sandbox-2vp | DTOs: + deviceName | task | P2 | - |
| 5 | auth-sandbox-8qk | KeycloakAdminClient: createDeviceCredential + deviceName | task | P2 | - |
| 6 | auth-sandbox-c79 | KeycloakAdminClient: FederatedIdentity-Methoden entfernen | task | P3 | 8qk |
| 7 | auth-sandbox-zdh | DeviceService: + deviceName (Eindeutigkeit pro User) | feature | P2 | 956, 8qk, c79 |
| 8 | auth-sandbox-e71 | AuthService.startLogin | feature | P2 | 956, 2vp, s22 |
| 9 | auth-sandbox-qls | AuthService.verifyChallenge | feature | P2 | 2vp, e71 |
| 10 | auth-sandbox-4l0 | JwtService: issueKeycloakAssertionToken entfernen | task | P3 | qls |
| 11 | auth-sandbox-7ta | [keycloak-ext] DeviceCredentialModel + Provider + Factory | feature | P2 | - |
| 12 | auth-sandbox-own | [keycloak-ext] DeviceLoginConditionAuthenticator | feature | P2 | 7ta |
| 13 | auth-sandbox-4gv | [keycloak-ext] SsoLoginConditionAuthenticator | feature | P2 | 7ta |
| 14 | auth-sandbox-w9x | [keycloak-ext] DeviceLoginTokenAuthenticator | feature | P2 | 7ta, own |
| 15 | auth-sandbox-edd | [keycloak-ext] SsoLoginTokenAuthenticator | feature | P2 | 4gv, w9x |
| 16 | auth-sandbox-n3h | Tests aktualisieren | task | P2 | zdh, e71, qls, c79 |

---

## Startreihenfolge

**Phase 1 (parallel startbar):**
- `auth-sandbox-6ye` (Flyway V11: + deviceName)
- `auth-sandbox-2vp` (DTOs: + deviceName)
- `auth-sandbox-8qk` (KeycloakAdminClient: + deviceName)
- `auth-sandbox-7ta` (Keycloak Credential Provider)

**Phase 2:**
- `auth-sandbox-956` (Device Entity: + deviceName) → nach 6ye
- `auth-sandbox-s22` (Challenge Entity: OHNE deviceId, + userId) → nach 6ye

**Phase 3:**
- `auth-sandbox-c79` (FederatedIdentity entfernen) → nach 8qk

**Phase 4:**
- `auth-sandbox-zdh` (DeviceService: + deviceName, Eindeutigkeit pro User) → nach 956, 8qk, c79

**Phase 5:**
- `auth-sandbox-e71` (startLogin) → nach 956, 2vp, s22
- `auth-sandbox-own` (DeviceLoginConditionAuthenticator) → nach 7ta
- `auth-sandbox-4gv` (SsoLoginConditionAuthenticator) → nach 7ta

**Phase 6:**
- `auth-sandbox-qls` (verifyChallenge) → nach 2vp, e71
- `auth-sandbox-w9x` (DeviceLoginTokenAuthenticator) → nach 7ta, own

**Phase 7:**
- `auth-sandbox-4l0` (JwtService bereinigen) → nach qls
- `auth-sandbox-edd` (SsoLoginTokenAuthenticator) → nach 4gv, w9x

**Phase 8:**
- `auth-sandbox-n3h` (Tests) → nach zdh, e71, qls, c79

---

## Teststrategy

- **Unit Tests:** AuthServiceTest, DeviceServiceTest an neuen Flow anpassen
- **E2E Tests:** Manuell via Frontend oder curl/wget testen
- **Linting:** `./gradlew check` (falls vorhanden)
