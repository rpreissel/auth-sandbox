package dev.authsandbox.keycloak;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.JWTAuthorizationGrantProvider;
import org.keycloak.cache.AlternativeLookupProvider;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.protocol.oidc.grants.JWTAuthorizationGrantValidator;
import org.keycloak.services.resources.IdentityBrokerService;

/**
 * Keycloak Authenticator SPI that validates the {@code login_token} extra parameter
 * passed in the Authorization Code Flow request.
 *
 * <p>The {@code login_token} is a short-lived JWT assertion issued by device-login.
 * The matching JWT Authorization Grant IdP is resolved dynamically from the {@code iss}
 * claim via {@link AlternativeLookupProvider#lookupIdentityProviderFromIssuer} —
 * no static config needed.
 *
 * <p>Validation logic mirrors {@code JWTAuthorizationGrantType} exactly:
 * issuer + subject + token-active + signature-algorithm are validated before delegating
 * the full JWT assertion check to {@link JWTAuthorizationGrantProvider#validateAuthorizationGrantAssertion}.
 *
 * <p>After successful validation the user is looked up via {@link FederatedIdentityModel}
 * using the {@code sub} claim from the brokered identity context.
 */
public class DeviceTokenAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(DeviceTokenAuthenticator.class);

    /** Auth session note key injected by Keycloak for extra authorization request params. */
    private static final String LOGIN_TOKEN_NOTE = "client_request_param_login_token";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        KeycloakSession session = context.getSession();
        String loginToken = context.getAuthenticationSession().getClientNote(LOGIN_TOKEN_NOTE);

        if (loginToken == null || loginToken.isBlank()) {
            LOG.warn("No login_token found in auth session notes");
            context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
            return;
        }

        try {
            // Build the validator — mirrors JWTAuthorizationGrantType.process().
            // The client is taken from the current auth session so that audience
            // validation can reference it if needed later; validateClient() is
            // intentionally not called here (no client_secret exchange in this flow).
            JWTAuthorizationGrantValidator authorizationGrantContext =
                    JWTAuthorizationGrantValidator.createValidator(
                            session,
                            context.getAuthenticationSession().getClient(),
                            loginToken,
                            /* scope */ null);

            // Validate mandatory claims (iss, sub).
            authorizationGrantContext.validateIssuer();
            authorizationGrantContext.validateSubject();

            // Validate that the token was issued for the requesting client (azp claim).
            // This prevents a login_token issued for client A from being used by client B,
            // even if client B shares the same authentication flow.
            // Keycloak maps the JWT "azp" claim to JsonWebToken.issuedFor — not to otherClaims.
            String requestingClientId = context.getAuthenticationSession().getClient().getClientId();
            String tokenAzp = authorizationGrantContext.getJWT().getIssuedFor();
            if (tokenAzp == null || !tokenAzp.equals(requestingClientId)) {
                LOG.warnf("login_token azp='%s' does not match requesting client '%s'",
                        tokenAzp, requestingClientId);
                context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }

            // Resolve the IdP from the issuer claim — same call as JWTAuthorizationGrantType.
            String jwtIssuer = authorizationGrantContext.getIssuer();
            LOG.debugf("login_token issuer: '%s'", jwtIssuer);
            AlternativeLookupProvider lookupProvider =
                    session.getProvider(AlternativeLookupProvider.class);
            IdentityProviderModel identityProviderModel =
                    lookupProvider.lookupIdentityProviderFromIssuer(session, jwtIssuer);

            if (identityProviderModel == null) {
                LOG.warnf("No IdP found for issuer '%s'", jwtIssuer);
                context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }

            if (!identityProviderModel.isEnabled()) {
                LOG.warnf("IdP '%s' is not enabled", identityProviderModel.getAlias());
                context.failure(AuthenticationFlowError.IDENTITY_PROVIDER_DISABLED);
                return;
            }

            // Get the typed JWTAuthorizationGrantProvider — same call as JWTAuthorizationGrantType.
            @SuppressWarnings("rawtypes")
            JWTAuthorizationGrantProvider jwtAuthorizationGrantProvider =
                    IdentityBrokerService.getIdentityProvider(
                            session, identityProviderModel, JWTAuthorizationGrantProvider.class);

            if (jwtAuthorizationGrantProvider == null) {
                LOG.errorf("IdP '%s' is not configured for JWT Authorization Grant",
                        identityProviderModel.getAlias());
                context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }

            // Validate token lifetime and signature algorithm.
            authorizationGrantContext.validateTokenActive(
                    jwtAuthorizationGrantProvider.getAllowedClockSkew(),
                    jwtAuthorizationGrantProvider.getMaxAllowedExpiration(),
                    jwtAuthorizationGrantProvider.isAssertionReuseAllowed());

            authorizationGrantContext.validateSignatureAlgorithm(
                    jwtAuthorizationGrantProvider.getAssertionSignatureAlg());

            // Delegate full JWT assertion validation to the IdP.
            LOG.debugf("Calling validateAuthorizationGrantAssertion for idp '%s'", identityProviderModel.getAlias());
            BrokeredIdentityContext brokeredCtx =
                    jwtAuthorizationGrantProvider.validateAuthorizationGrantAssertion(
                            authorizationGrantContext);
            LOG.debugf("validateAuthorizationGrantAssertion returned: %s", brokeredCtx);

            if (brokeredCtx == null) {
                LOG.warn("login_token validation returned null BrokeredIdentityContext");
                context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }

            // Look up the local user via the federated identity link.
            String externalUserId = brokeredCtx.getId();
            String idpAlias = brokeredCtx.getIdpConfig().getAlias();

            FederatedIdentityModel federatedIdentityModel = new FederatedIdentityModel(
                    idpAlias, externalUserId, brokeredCtx.getUsername(), brokeredCtx.getToken());

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

            LOG.infof("Device token authenticator: authenticated user '%s'", user.getUsername());
            context.setUser(user);
            context.success();

        } catch (Exception e) {
            LOG.warnf(e, "login_token validation failed");
            context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
        }
    }

    // -----------------------------------------------------------------------
    // Authenticator lifecycle — no form, no user pre-requisite
    // -----------------------------------------------------------------------

    @Override
    public void action(AuthenticationFlowContext context) {
        // This authenticator never renders a form — action is not used.
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
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}
