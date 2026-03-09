package dev.authsandbox.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;

import java.util.Map;

public class SsoLoginConditionAuthenticator implements org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator {

    private static final Logger LOG = Logger.getLogger(SsoLoginConditionAuthenticator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final SsoLoginConditionAuthenticator SINGLETON = new SsoLoginConditionAuthenticator();

    @Override
    public boolean evaluate(org.keycloak.authentication.AuthenticationExecutionModel execution, org.keycloak.authentication.AuthenticationFlowContext context) {
        String loginToken = context.getAuthenticationSession().getClientNote("login_token");
        
        if (loginToken == null || loginToken.isBlank()) {
            LOG.debug("No login_token present - condition not met");
            context.getAuthenticationSession().setNote("SSO_LOGIN_CONDITION", "false");
            return false;
        }

        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(loginToken));
            Map<String, Object> tokenData = MAPPER.readValue(decoded, Map.class);
            
            String type = (String) tokenData.get("type");
            boolean isSso = "sso".equals(type);
            
            context.getAuthenticationSession().setNote("SSO_LOGIN_CONDITION", String.valueOf(isSso));
            
            if (isSso) {
                LOG.debug("login_token type is 'sso' - condition met");
                return true;
            } else {
                LOG.debug("login_token type is '" + type + "' - condition not met");
                return false;
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse login_token", e);
            return false;
        }
    }

    @Override
    public void action(org.keycloak.authentication.AuthenticationExecutionModel execution, org.keycloak.authentication.AuthenticationFlowContext context) {
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, org.keycloak.models.UserModel user) {
    }

    @Override
    public boolean configuredFor(KeycloakSession session, org.keycloak.models.RealmModel realm, org.keycloak.models.UserModel user) {
        return true;
    }

    public static class Factory implements org.keycloak.authentication.AuthenticationFactory<SsoLoginConditionAuthenticator> {
        @Override
        public String getId() {
            return "sso-login-condition";
        }

        @Override
        public SsoLoginConditionAuthenticator create(KeycloakSession session) {
            return SsoLoginConditionAuthenticator.SINGLETON;
        }
    }
}
