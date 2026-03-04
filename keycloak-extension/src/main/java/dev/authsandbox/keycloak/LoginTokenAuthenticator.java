package dev.authsandbox.keycloak;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowCallback;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.util.AcrStore;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Key;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LoginTokenAuthenticator implements AuthenticationFlowCallback {

    private static final Logger LOG = Logger.getLogger(LoginTokenAuthenticator.class);

    private static final String LOGIN_TOKEN_NOTE = "client_request_param_login_token";

    private final KeycloakSession session;
    private final HttpClient httpClient;
    private final Map<String, CachedJWKSet> jwksCache = new ConcurrentHashMap<>();

    public LoginTokenAuthenticator(KeycloakSession session) {
        this.session = session;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        String loginToken = context.getAuthenticationSession().getClientNote(LOGIN_TOKEN_NOTE);

        if (loginToken == null || loginToken.isBlank()) {
            LOG.warn("No login_token found in auth session notes");
            context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
            return;
        }

        try {
            ClientModel client = context.getAuthenticationSession().getClient();
            String clientId = client.getClientId();
            String clientJwksUrl = getClientJwksUrl(client);

            if (clientJwksUrl == null || clientJwksUrl.isBlank()) {
                LOG.warnf("Client '%s' has no jwks_url configured", clientId);
                context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }

            String trustedClientIdsConfig = getTrustedClientIds(context);
            if (!isTrustedClient(clientId, trustedClientIdsConfig)) {
                LOG.warnf("Client '%s' is not in trusted client IDs list", clientId);
                context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }

            PublicKey publicKey = getPublicKey(clientId, clientJwksUrl);

            Claims claims = validateJwt(loginToken, publicKey, clientId);

            String jti = claims.getId();
            if (jti == null || jti.isBlank()) {
                LOG.warn("login_token has no jti (JWT ID) claim - required for single-use validation");
                context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }

            long tokenExpiry = claims.getExpiration().getTime();
            long currentTime = System.currentTimeMillis();
            long ttlSeconds = Math.max(1, (tokenExpiry - currentTime) / 1000);

            SingleUseObjectProvider singleUseProvider = session.getProvider(SingleUseObjectProvider.class);
            if (singleUseProvider != null) {
                boolean wasNotUsed = singleUseProvider.putIfAbsent("logintoken:" + jti, ttlSeconds);
                if (!wasNotUsed) {
                    LOG.warnf("login_token with jti '%s' has already been used", jti);
                    context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
                    return;
                }
                LOG.infof("login_token jti '%s' registered for single-use (ttl=%ds)", jti, ttlSeconds);
            } else {
                LOG.warn("SingleUseObjectProvider not available - single-use validation skipped");
            }

            // DEBUG: Log all claims to diagnose jti vs sub issue
            LOG.infof("JWT claims - getSubject(): '%s', getId(): '%s', getIssuer(): '%s'",
                    claims.getSubject(), claims.getId(), claims.getIssuer());
            LOG.infof("JWT raw claims map: %s", claims);

            String issuer = claims.getIssuer();
            String subject = claims.getSubject();

            if (issuer == null || issuer.isBlank()) {
                LOG.warn("login_token has no issuer claim");
                context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }

            if (subject == null || subject.isBlank()) {
                LOG.warn("login_token has no subject claim");
                context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }

            RealmModel realm = session.getContext().getRealm();
            IdentityProviderModel identityProviderModel = findIdpByIssuer(realm, issuer);

            if (identityProviderModel == null) {
                LOG.warnf("No IdP found for issuer '%s'", issuer);
                context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }

            if (!identityProviderModel.isEnabled()) {
                LOG.warnf("IdP '%s' is not enabled", identityProviderModel.getAlias());
                context.failure(AuthenticationFlowError.IDENTITY_PROVIDER_DISABLED);
                return;
            }

            String externalUserId = claims.getSubject();
            LOG.infof("External user ID from claims.getSubject(): '%s'", externalUserId);
            String idpAlias = identityProviderModel.getAlias();

            FederatedIdentityModel federatedIdentityModel = new FederatedIdentityModel(
                    idpAlias, externalUserId, externalUserId, null);

            UserModel user = session.users()
                    .getUserByFederatedIdentity(context.getRealm(), federatedIdentityModel);

            if (user == null) {
                LOG.warnf("No user found for federated identity sub='%s' idp='%s'",
                        externalUserId, idpAlias);
                context.failure(AuthenticationFlowError.INVALID_USER);
                return;
            }

            if (!user.isEnabled()) {
                LOG.warnf("User '%s' is not enabled", user.getUsername());
                context.failure(AuthenticationFlowError.USER_DISABLED);
                return;
            }

            LOG.infof("Login token authenticator: authenticated user '%s'", user.getUsername());
            context.setUser(user);
            context.success();

        } catch (JwtException e) {
            LOG.warnf(e, "login_token JWT validation failed");
            context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
        } catch (Exception e) {
            LOG.warnf(e, "login_token validation failed");
            context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
        }
    }

    private IdentityProviderModel findIdpByIssuer(RealmModel realm, String issuer) {
        var identityProviders = realm.getIdentityProvidersStream().toList();
        for (IdentityProviderModel idp : identityProviders) {
            String idpIssuer = idp.getConfig().get("issuer");
            if (issuer.equals(idpIssuer)) {
                return idp;
            }
        }
        return null;
    }

    private String getClientJwksUrl(ClientModel client) {
        return client.getAttribute("jwks_url");
    }

    private String getTrustedClientIds(AuthenticationFlowContext context) {
        var config = context.getAuthenticatorConfig();
        if (config == null) {
            return null;
        }
        return config.getConfig().get("trusted-client-ids");
    }

    private boolean isTrustedClient(String clientId, String trustedClientIdsConfig) {
        if (trustedClientIdsConfig == null || trustedClientIdsConfig.isBlank()) {
            return true;
        }
        Set<String> trustedIds = Set.of(trustedClientIdsConfig.split(","));
        return trustedIds.contains(clientId.trim());
    }

    private PublicKey getPublicKey(String clientId, String jwksUrl) throws Exception {
        CachedJWKSet cached = jwksCache.get(clientId);
        if (cached != null && !cached.isExpired()) {
            return cached.getPublicKey();
        }

        String jwksJson = fetchJwks(jwksUrl);
        PublicKey publicKey = parseJwk(jwksJson);

        jwksCache.put(clientId, new CachedJWKSet(publicKey, Duration.ofMinutes(15)));
        return publicKey;
    }

    private String fetchJwks(String jwksUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(jwksUrl))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch JWKS: " + response.statusCode());
        }

        return response.body();
    }

    @SuppressWarnings("unchecked")
    private PublicKey parseJwk(String jwksJson) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> jwks;
        try {
            jwks = mapper.readValue(jwksJson, Map.class);
        } catch (Exception e) {
            throw new JwtException("Failed to parse JWKS JSON", e);
        }

        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
        if (keys == null || keys.isEmpty()) {
            throw new JwtException("No keys found in JWKS");
        }

        for (Map<String, Object> key : keys) {
            String kty = (String) key.get("kty");
            if ("RSA".equals(kty)) {
                String nStr = (String) key.get("n");
                String eStr = (String) key.get("e");
                if (nStr != null && eStr != null) {
                    byte[] nBytes = Base64.getUrlDecoder().decode(nStr);
                    byte[] eBytes = Base64.getUrlDecoder().decode(eStr);

                    java.math.BigInteger n = new java.math.BigInteger(1, nBytes);
                    java.math.BigInteger e = new java.math.BigInteger(1, eBytes);

                    java.security.spec.RSAPublicKeySpec spec =
                            new java.security.spec.RSAPublicKeySpec(n, e);
                    try {
                        java.security.KeyFactory keyFactory =
                                java.security.KeyFactory.getInstance("RSA");
                        return keyFactory.generatePublic(spec);
                    } catch (Exception ex) {
                        LOG.warnf(ex, "Failed to parse RSA public key");
                    }
                }
            }
        }

        throw new JwtException("No usable RSA key found in JWKS");
    }

    private Claims validateJwt(String token, PublicKey publicKey, String expectedAud) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }

    @Override
    public void onParentFlowSuccess(AuthenticationFlowContext context) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        AcrStore acrStore = new AcrStore(session, authSession);

        int newLoa = 2;
        
        acrStore.setLevelAuthenticated(newLoa);
        
        String loaMap = authSession.getAuthNote(Constants.LOA_MAP);
        LOG.infof("onParentFlowSuccess: LOA_MAP=%s", loaMap);
        if (loaMap != null) {
            authSession.setUserSessionNote(Constants.LOA_MAP, loaMap);
            LOG.infof("onParentFlowSuccess: copied LOA_MAP to user session");
        }
    }

    @Override
    public void onTopFlowSuccess(AuthenticationFlowModel topFlow) {
        AuthenticationSessionModel authSession = session.getContext().getAuthenticationSession();
        AcrStore acrStore = new AcrStore(session, authSession);

        LOG.infof("Updating authenticated levels in authSession '%s' to user session note for future authentications: %s", 
            authSession.getParentSession().getId(), authSession.getAuthNote(Constants.LOA_MAP));
        authSession.setUserSessionNote(Constants.LOA_MAP, authSession.getAuthNote(Constants.LOA_MAP));
    }

    private static class CachedJWKSet {
        private final PublicKey publicKey;
        private final long expiresAt;

        public CachedJWKSet(PublicKey publicKey, Duration ttl) {
            this.publicKey = publicKey;
            this.expiresAt = System.currentTimeMillis() + ttl.toMillis();
        }

        public PublicKey getPublicKey() {
            return publicKey;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
