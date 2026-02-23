# Keycloak-Konfiguration — was wird angelegt und warum

Dieses Dokument beschreibt alle Keycloak-Ressourcen, die OpenTofu in der lokalen Entwicklungsumgebung anlegt. Es erklärt nicht, wie die OpenTofu-Dateien aufgebaut sind, sondern **was in Keycloak entsteht** und **welche Rolle jedes Element im Authentifizierungsablauf spielt**.

Der vollständige Ablauf, den diese Konfiguration ermöglicht:

1. Die mobile App schickt eine Login-Anfrage an `device-login`.
2. `device-login` stellt einen signierten Device-JWT aus und leitet die App zu Keycloak weiter.
3. Keycloak validiert den Device-JWT über den konfigurierten Identity Provider.
4. Der custom Authentication Flow prüft, ob der Token korrekt strukturiert ist.
5. Keycloak tauscht den Device-JWT gegen OIDC-Tokens (Access Token, ID Token, Refresh Token) und liefert sie an `device-login` zurück.
6. `device-login` reicht die OIDC-Tokens an die App weiter.

---

## Realm `auth-sandbox`

Ein Keycloak-Realm ist ein in sich geschlossener Namensraum mit eigenen Benutzern, Clients, Identity Providern und Konfigurationen. Der Standard-Realm `master` dient ausschließlich der Keycloak-Administration und soll für eigene Anwendungen nie genutzt werden.

Der Realm `auth-sandbox` wird deshalb als separater Realm angelegt, der ausschließlich für dieses Projekt zuständig ist. Alle folgenden Elemente (Clients, IdP, Flows) gehören zu diesem Realm und sind von anderen Realms vollständig isoliert.

Die Token-Laufzeiten werden auf kurze Werte gesetzt (Access Token 5 Minuten, SSO-Session 30 Minuten idle), weil Device-basierte Authentifizierung keine langen Browser-Sessions kennt — das Gerät holt bei Bedarf über den Device-JWT neue Tokens.

---

## Client `device-login-client`

Ein Keycloak-Client repräsentiert eine Anwendung, die Tokens von Keycloak anfordert. Dieser Client steht für den `device-login`-Dienst in seiner Rolle als OIDC-Relying Party, d.h. als die Anwendung, die am Ende des Flows OIDC-Tokens für den Benutzer entgegennimmt.

**Warum Standard Authorization Code Flow?**
Nach der Validierung des Device-JWT durch den Identity Provider leitet Keycloak mit einem kurzlebigen Authorization Code an `device-login` zurück. `device-login` tauscht diesen Code serverseitig gegen Tokens — das ist der Standard Code Flow. Browser oder App bekommen nie direkt Tokens zu sehen, nur `device-login` kennt das Client-Secret und führt den Tausch durch.

**Warum CONFIDENTIAL?**
`device-login` ist ein serverseitiger Dienst, der sein Client-Secret sicher verwahren kann. `CONFIDENTIAL` erzwingt, dass der Token-Endpunkt nur mit Client-Secret antwortet — ein reines Frontend oder eine native App ohne sicheres Secret würde `PUBLIC` verwenden. Hier ist das Secret nötig, damit Keycloak sicher weiß, dass tatsächlich `device-login` den Code einlöst und kein fremder Aufrufer.

**Warum genau diese Redirect-URI?**
Keycloak sendet den Authorization Code ausschließlich an vorab registrierte URIs. Ist die URI nicht eingetragen, verweigert Keycloak die Weiterleitung — das verhindert, dass ein Angreifer den Code an eine eigene URI umleiten kann. Die eingetragene URI `https://device-login.localhost:8443/api/v1/auth/callback` ist der Callback-Endpunkt von `device-login`, der den Code entgegennimmt.

---

## Client `device-login-admin`

Dieser Client ermöglicht es dem `device-login`-Dienst, die **Keycloak Admin API** zu nutzen — ohne menschliche Interaktion und ohne Benutzerpasswort. Er verwendet den OAuth2 Client Credentials Grant: `device-login` authentifiziert sich direkt mit Client-ID und Client-Secret und erhält ein Access Token für API-Zugriffe.

Ein separater Client ist notwendig, weil die Administrationsberechtigungen klar vom normalen Benutzer-Auth-Flow getrennt sein müssen. Der `device-login-client` für den Code Flow darf nie Admin-Rechte besitzen — ein kompromittiertes Client-Secret des einen Clients soll nicht den anderen Bereich öffnen.

**Warum braucht dieser Client Service-Account-Rollen?**
Keycloak vergibt Admin-API-Berechtigungen über Rollen, nicht über generische Flags. Das Service-Account des Clients bekommt genau die drei Rollen, die `device-login` tatsächlich benötigt:

- **`view-users`** — `device-login` muss prüfen können, ob ein Keycloak-Benutzer zu einer gegebenen User-ID existiert, bevor ein Gerät registriert wird.
- **`manage-users`** — `device-login` legt beim Geräte-Login einen Keycloak-Benutzer an, falls dieser noch nicht existiert, und verknüpft ihn mit der Device-ID.
- **`manage-identity-providers`** — `device-login` muss programmatisch Identity-Provider-Links für Benutzer erzeugen können, damit Keycloak weiß, welcher Keycloak-User zu welchem Device-JWT-Subject gehört.

Keine anderen Rollen werden vergeben. `device-login` kann damit keine Clients, Realms oder Keycloak-Einstellungen verändern — das Principle of Least Privilege ist gewahrt.

---

## Identity Provider `device-login-idp` (JWT Authorization Grant)

Keycloak kennt verschiedene Identity-Provider-Typen: soziale Logins (Google, GitHub), andere OIDC-Provider oder SAML-Systeme. Der hier verwendete Typ **JWT Authorization Grant** ist ein Keycloak-spezifisches Feature, das einen signierten JWT als primären Authentifizierungsnachweis akzeptiert — ohne Passwort, ohne Browser-Redirect zu einem externen System.

`device-login` stellt für jedes authentifizierte Gerät einen signierten Device-JWT aus. Dieser JWT enthält als `sub` (Subject) die Keycloak-User-ID und als `iss` (Issuer) die URL von `device-login`. Keycloak muss diesen Token validieren können, bevor es ihn als Identitätsnachweis akzeptiert.

**Warum JWKS statt statischem Public Key?**
Das JWKS-Protokoll erlaubt Keycloak, den aktuellen öffentlichen RSA-Schlüssel von `device-login` dynamisch abzurufen. Wird das Schlüsselpaar von `device-login` rotiert, genügt ein Neustart von `device-login` — Keycloak holt beim nächsten Token-Zugriff automatisch den neuen Schlüssel, ohne dass Keycloak neu konfiguriert werden muss.

**Warum muss der Issuer explizit eingetragen sein?**
Der `iss`-Claim im Device-JWT wird gegen den konfigurierten Issuer-Wert geprüft. Stimmt er nicht überein, lehnt Keycloak den Token ab. Das verhindert, dass ein anderer Dienst, der zufällig denselben JWKS-Endpunkt kennt, gültige Tokens für diesen IdP ausstellen kann — Issuer-Binding ist eine grundlegende JWT-Sicherheitsmaßnahme.

---

## Authentication Flow `login-token-flow`

Keycloak führt bei jeder Anmeldung über einen Identity Provider einen konfigurierten Authentication Flow aus. Der Standard-Flow (`first broker login`) ist für das erstmalige Verknüpfen eines sozialen Logins mit einem lokalen Konto gedacht — er fragt nach E-Mail-Bestätigung, zeigt Profilseiten und ist für maschinelle Device-Authentifizierung ungeeignet.

Der custom Flow `login-token-flow` enthält genau eine Execution: den **`LoginTokenAuthenticator`**, der im Keycloak-Extension-JAR (`keycloak-extension/`) implementiert ist. Dieser Authenticator prüft, ob der als `login_token` übergebene JWT alle nötigen Claims enthält (insbesondere das Subject, das Keycloak als User-ID interpretiert) und gibt den Authentifizierungsschritt als erfolgreich zurück, ohne weitere Interaktion zu fordern.

Die Execution ist als **REQUIRED** konfiguriert: schlägt sie fehl, bricht der gesamte Flow ab und Keycloak stellt keine Tokens aus. Gibt es keinen gültigen Device-JWT, gibt es keine OIDC-Tokens.

Dieser Flow wird dem `device-login-idp` als Post-Login-Flow zugewiesen, sodass Keycloak ihn immer dann ausführt, wenn ein Token über diesen IdP eingelöst wird.

---

## Zusammenfassung der Abhängigkeiten

```
Realm auth-sandbox
├── Client device-login-client        ← empfängt OIDC-Tokens nach dem Code Flow
├── Client device-login-admin         ← Admin API Zugriff
│   └── Service Account Rollen        ← view-users, manage-users, manage-identity-providers
├── Identity Provider device-login-idp
│   └── validiert Device-JWTs via JWKS
└── Authentication Flow login-token-flow
    └── LoginTokenAuthenticator      ← aus keycloak-extension/
        └── gebunden an device-login-idp als Post-Login-Flow
```

---

## Anwendung

```bash
cd tofu
cp terraform.tfvars.example terraform.tfvars
# sensitive Werte aus .env eintragen

tofu init
tofu plan
tofu apply
```

`terraform.tfvars` enthält Secrets und ist über `.gitignore` vom Commit ausgeschlossen.

**Voraussetzung:** Das Keycloak-Extension-JAR muss vor `podman compose up` gebaut worden sein (`cd keycloak-extension && ./gradlew jar`), damit der `LoginTokenAuthenticator`-Provider in Keycloak verfügbar ist, wenn `tofu apply` den Authentication Flow anlegt.
