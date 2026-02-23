package dev.authsandbox.keycloak.userstorage;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.storage.UserStorageProviderFactory;

public class LocalUserStorageProviderFactory
        implements UserStorageProviderFactory<LocalUserStorageProvider> {

    public static final String PROVIDER_ID = "local-user-storage";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Proxy provider that delegates to Keycloak local storage. "
                + "Sets federationLink on new users so that custom credential "
                + "validation can be plugged in later.";
    }

    @Override
    public LocalUserStorageProvider create(KeycloakSession session, ComponentModel model) {
        return new LocalUserStorageProvider(session, model);
    }
}
