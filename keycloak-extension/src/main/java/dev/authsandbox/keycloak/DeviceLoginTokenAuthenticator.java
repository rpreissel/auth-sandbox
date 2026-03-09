package dev.authsandbox.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowCallback;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

public class DeviceLoginTokenAuthenticator implements AuthenticationFlowCallback {

    private static final Logger LOG = Logger.getLogger(DeviceLoginTokenAuthenticator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public DeviceLoginTokenAuthenticator(KeycloakSession session) {
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
            String decoded = new String(Base64.getUrlDecoder().decode(loginToken));
            Map<String, Object> tokenData = MAPPER.readValue(decoded, Map.class);
            
            String type = (String) tokenData.get("type");
            if (!"device".equals(type)) {
                LOG.warn("Invalid login_token type: " + type);
                context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }
            
            String userId = (String) tokenData.get("sub");
            String encryptedKey = (String) tokenData.get("encryptedKey");
            String encryptedData = (String) tokenData.get("encryptedData");
            String iv = (String) tokenData.get("iv");
            String signature = (String) tokenData.get("signature");
            
            RealmModel realm = context.getRealm();
            UserModel user = context.getSession().users().getUserByUsername(realm, userId);
            
            if (user == null) {
                LOG.warn("User not found: " + userId);
                context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }
            
            var credentials = user.credentialManager()
                    .getStoredCredentialsByTypeStream(DeviceCredentialModel.TYPE)
                    .toList();
            
            if (credentials.isEmpty()) {
                LOG.warn("No device-login credential found for user: " + userId);
                context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }
            
            CredentialModel cred = credentials.get(0);
            DeviceCredentialModel deviceCred = DeviceCredentialModel.createFromCredentialModel(cred);
            
            String encPrivKeyB64 = deviceCred.getEncPrivKey();
            byte[] encPrivKeyBytes = Base64.getDecoder().decode(encPrivKeyB64);
            RSAPrivateKey encPrivKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(encPrivKeyBytes));
            
            byte[] aesKeyBytes = decryptRsa(encPrivKey, Base64.getDecoder().decode(encryptedKey));
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            
            byte[] decryptedData = decryptAes(aesKey, Base64.getDecoder().decode(encryptedData), 
                    Base64.getDecoder().decode(iv));
            String payloadJson = new String(decryptedData, StandardCharsets.UTF_8);
            Map<String, Object> payload = MAPPER.readValue(payloadJson, Map.class);
            
            String payloadSignature = (String) payload.get("signature");
            if (payloadSignature == null || !payloadSignature.equals(signature)) {
                LOG.warn("Signature mismatch");
                context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_CREDENTIALS);
                return;
            }
            
            LOG.info("Device login successful for user: " + userId);
            context.setUser(user);
            context.success();
            
        } catch (Exception e) {
            LOG.error("Device login failed", e);
            context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_CREDENTIALS);
        }
    }
    
    private byte[] decryptRsa(RSAPrivateKey privKey, byte[] ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privKey);
        return cipher.doFinal(ciphertext);
    }
    
    private byte[] decryptAes(SecretKey key, byte[] ciphertext, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(ciphertext);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
    }
    
    public static class Factory implements org.keycloak.authentication.AuthenticationFactory<DeviceLoginTokenAuthenticator> {
        @Override
        public String getId() {
            return "device-login-token";
        }

        @Override
        public DeviceLoginTokenAuthenticator create(KeycloakSession session) {
            return new DeviceLoginTokenAuthenticator(session);
        }
    }
}
