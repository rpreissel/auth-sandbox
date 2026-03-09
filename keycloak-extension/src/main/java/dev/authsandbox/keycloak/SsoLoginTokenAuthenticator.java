package dev.authsandbox.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowCallback;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.Map;

public class SsoLoginTokenAuthenticator implements AuthenticationFlowCallback {

    private static final Logger LOG = Logger.getLogger(SsoLoginTokenAuthenticator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SsoLoginTokenAuthenticator(KeycloakSession session) {
    }

    @Override
    public void evaluate(AuthenticationFlowContext context) {
        String loginToken = context.getAuthenticationSession().getClientNote("login_token");
        
        if (loginToken == null || loginToken.isBlank()) {
            LOG.warn("No login_token in authentication session");
            context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_CREDENTIALS);
            return;
        }

        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(loginToken));
            Map<String, Object> tokenData = MAPPER.readValue(decoded, Map.class);
            
            String type = (String) tokenData.get("type");
            if (!"sso".equals(type)) {
                LOG.warn("Invalid login_token type: " + type);
                context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }
            
            String userId = (String) tokenData.get("sub");
            String requestUri = (String) tokenData.get("request_uri");
            String sessionId = (String) tokenData.get("session_id");
            
            RealmModel realm = context.getRealm();
            UserModel user = context.getSession().users().getUserByUsername(realm, userId);
            
            if (user == null) {
                LOG.warn("User not found: " + userId);
                context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }
            
            LOG.info("SSO login successful for user: " + userId);
            context.setUser(user);
            context.success();
            
        } catch (Exception e) {
            LOG.error("SSO login failed", e);
            context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_CREDENTIALS);
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
    }
    
    public static class Factory implements org.keycloak.authentication.AuthenticationFactory<SsoLoginTokenAuthenticator> {
        @Override
        public String getId() {
            return "sso-login-token";
        }

        @Override
        public SsoLoginTokenAuthenticator create(KeycloakSession session) {
            return new SsoLoginTokenAuthenticator(session);
        }
    }
}
