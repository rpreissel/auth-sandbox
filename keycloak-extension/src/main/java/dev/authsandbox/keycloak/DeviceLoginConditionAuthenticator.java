package dev.authsandbox.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationExecutionModel;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.models.KeycloakSession;

import java.util.Map;

public class DeviceLoginConditionAuthenticator implements org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator {

    private static final Logger LOG = Logger.getLogger(DeviceLoginConditionAuthenticator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final DeviceLoginConditionAuthenticator SINGLETON = new DeviceLoginConditionAuthenticator();

    @Override
    public boolean evaluate(AuthenticationExecutionModel execution, AuthenticationFlowContext context) {
        String loginToken = context.getAuthenticationSession().getClientNote("login_token");
        
        if (loginToken == null || loginToken.isBlank()) {
            LOG.debug("No login_token present - condition not met");
            context.getAuthenticationSession().setNote("DEVICE_LOGIN_CONDITION", "false");
            return false;
        }

        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(loginToken));
            Map<String, Object> tokenData = MAPPER.readValue(decoded, Map.class);
            
            String type = (String) tokenData.get("type");
            boolean isDevice = "device".equals(type);
            
            context.getAuthenticationSession().setNote("DEVICE_LOGIN_CONDITION", String.valueOf(isDevice));
            
            if (isDevice) {
                LOG.debug("login_token type is 'device' - condition met");
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
    public void action(AuthenticationExecutionModel execution, AuthenticationFlowContext context) {
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

    public static class Factory implements org.keycloak.authentication.AuthenticationFactory<DeviceLoginConditionAuthenticator> {
        @Override
        public String getId() {
            return "device-login-condition";
        }

        @Override
        public DeviceLoginConditionAuthenticator create(KeycloakSession session) {
            return DeviceLoginConditionAuthenticator.SINGLETON;
        }
    }
}
