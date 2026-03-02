package dev.authsandbox.keycloak;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticationFlowCallbackFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import java.util.List;
import java.util.ArrayList;

public class LoginTokenAuthenticatorFactory implements AuthenticationFlowCallbackFactory {

    public static final String PROVIDER_ID = "login-token-authenticator";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Login Token Authenticator";
    }

    @Override
    public String getReferenceCategory() {
        return "login-token";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[]{
                AuthenticationExecutionModel.Requirement.REQUIRED,
                AuthenticationExecutionModel.Requirement.DISABLED
        };
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Validates a login_token JWT passed as an extra authorization request parameter "
                + "using Keycloak's built-in JWT Authorization Grant Identity Provider.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> configProperties = new ArrayList<>();
        
        ProviderConfigProperty trustedClientIds = new ProviderConfigProperty();
        trustedClientIds.setName("trusted-client-ids");
        trustedClientIds.setLabel("Trusted Client IDs");
        trustedClientIds.setType(ProviderConfigProperty.STRING_TYPE);
        trustedClientIds.setHelpText("Comma-separated list of client IDs that are allowed to authenticate using login tokens");
        
        configProperties.add(trustedClientIds);
        return configProperties;
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new LoginTokenAuthenticator(session);
    }

    @Override
    public void init(Config.Scope config) {
        // no-op
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}
