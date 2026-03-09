package dev.authsandbox.keycloak;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.keycloak.credential.CredentialModel;

import java.util.Map;
import java.util.stream.Stream;

public class DeviceCredentialModel extends CredentialModel {

    public static final String TYPE = "device-login";
    private static final ObjectMapper mapper = new ObjectMapper();

    public static DeviceCredentialModel create(
            String publicKey, String publicKeyHash, String encPrivKey) {
        DeviceCredentialModel model = new DeviceCredentialModel();
        model.setType(TYPE);
        try {
            model.setCredentialData(mapper.writeValueAsString(Map.of(
                "publicKey", publicKey,
                "publicKeyHash", publicKeyHash
            )));
            model.setSecretData(mapper.writeValueAsString(Map.of(
                "encPrivKey", encPrivKey
            )));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create device credential model", e);
        }
        return model;
    }

    public static DeviceCredentialModel createFromCredentialModel(CredentialModel model) {
        DeviceCredentialModel m = new DeviceCredentialModel();
        m.setUserLabel(model.getUserLabel());
        m.setType(model.getType());
        m.setCreatedDate(model.getCreatedDate());
        m.setId(model.getId());
        m.setCredentialData(model.getCredentialData());
        m.setSecretData(model.getSecretData());
        return m;
    }

    public String getPublicKey() {
        try {
            Map<String, String> data = mapper.readValue(getCredentialData(),
                new TypeReference<Map<String, String>>() {});
            return data.get("publicKey");
        } catch (Exception e) {
            return null;
        }
    }

    public String getPublicKeyHash() {
        try {
            Map<String, String> data = mapper.readValue(getCredentialData(),
                new TypeReference<Map<String, String>>() {});
            return data.get("publicKeyHash");
        } catch (Exception e) {
            return null;
        }
    }

    public String getEncPrivKey() {
        try {
            Map<String, String> data = mapper.readValue(getSecretData(),
                new TypeReference<Map<String, String>>() {});
            return data.get("encPrivKey");
        } catch (Exception e) {
            return null;
        }
    }

    public static Stream<DeviceCredentialModel> getCredentialStream(Iterable<CredentialModel> models) {
        return java.util.stream.StreamSupport.stream(models.spliterator(), false)
            .filter(c -> TYPE.equals(c.getType()))
            .map(DeviceCredentialModel::createFromCredentialModel);
    }
}
