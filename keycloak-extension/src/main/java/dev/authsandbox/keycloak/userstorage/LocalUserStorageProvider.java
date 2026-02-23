package dev.authsandbox.keycloak.userstorage;

import java.util.Map;
import java.util.stream.Stream;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.UserStoragePrivateUtil;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;
import org.keycloak.storage.user.UserQueryMethodsProvider;
import org.keycloak.storage.user.UserRegistrationProvider;

/**
 * Proxy UserStorage provider that delegates all user data operations to
 * Keycloak's own local JPA storage.
 *
 * <p>The sole purpose of this provider is to set {@code federationLink} on
 * newly created users so that {@link CredentialInputValidator#isValid} is
 * invoked by Keycloak for those users. This allows external credential
 * validation to be plugged in here later without any structural changes.
 *
 * <p>Existing users without a {@code federationLink} must be migrated or
 * deleted manually before this provider takes effect for them.
 */
public class LocalUserStorageProvider
        implements UserStorageProvider,
                   UserLookupProvider,
                   UserRegistrationProvider,
                   UserQueryMethodsProvider,
                   CredentialInputValidator {

    private static final Logger log = Logger.getLogger(LocalUserStorageProvider.class);

    private final KeycloakSession session;
    private final ComponentModel model;

    public LocalUserStorageProvider(KeycloakSession session, ComponentModel model) {
        this.session = session;
        this.model = model;
    }

    // -------------------------------------------------------------------------
    // UserLookupProvider
    // -------------------------------------------------------------------------

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        // StorageId format: "f:<componentId>:<externalId>"
        // We store the KC-local UUID as the externalId so we can look it up directly.
        StorageId storageId = new StorageId(id);
        String localId = storageId.getExternalId();
        log.debugf("getUserById: id=%s localId=%s", id, localId);
        return localStorage().getUserById(realm, localId);
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        log.debugf("getUserByUsername: username=%s", username);
        return localStorage().getUserByUsername(realm, username);
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        log.debugf("getUserByEmail: email=%s", email);
        return localStorage().getUserByEmail(realm, email);
    }

    // -------------------------------------------------------------------------
    // UserRegistrationProvider
    //
    // addUser() creates the local KC user AND immediately sets federationLink
    // so that CredentialInputValidator.isValid() is dispatched to this provider.
    // -------------------------------------------------------------------------

    @Override
    public UserModel addUser(RealmModel realm, String username) {
        log.debugf("addUser: username=%s", username);
        UserModel user = localStorage().addUser(realm, username);
        user.setFederationLink(model.getId());
        return user;
    }

    @Override
    public boolean removeUser(RealmModel realm, UserModel user) {
        log.debugf("removeUser: username=%s", user.getUsername());
        return localStorage().removeUser(realm, user);
    }

    // -------------------------------------------------------------------------
    // UserQueryMethodsProvider — required for Admin Console user list
    // -------------------------------------------------------------------------

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm,
                                                  Map<String, String> params,
                                                  Integer firstResult,
                                                  Integer maxResults) {
        return localStorage().searchForUserStream(realm, params, firstResult, maxResults);
    }

    @Override
    public Stream<UserModel> getGroupMembersStream(RealmModel realm,
                                                    GroupModel group,
                                                    Integer firstResult,
                                                    Integer maxResults) {
        return localStorage().getGroupMembersStream(realm, group, firstResult, maxResults);
    }

    @Override
    public Stream<UserModel> searchForUserByUserAttributeStream(RealmModel realm,
                                                                 String attrName,
                                                                 String attrValue) {
        return localStorage().searchForUserByUserAttributeStream(realm, attrName, attrValue);
    }

    // -------------------------------------------------------------------------
    // CredentialInputValidator
    //
    // Handles password validation for all users linked to this provider via
    // federationLink. Currently always returns true — replace isValid() with
    // external credential validation (e.g. device-login) when ready.
    // -------------------------------------------------------------------------

    @Override
    public boolean supportsCredentialType(String credentialType) {
        return PasswordCredentialModel.TYPE.equals(credentialType);
    }

    @Override
    public boolean isConfiguredFor(RealmModel realm, UserModel user, String credentialType) {
        return supportsCredentialType(credentialType);
    }

    @Override
    public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType())) {
            return false;
        }
        log.debugf("isValid: always returning true for user=%s (placeholder)", user.getUsername());
        // TODO: replace with external credential validation (e.g. device-login)
        return true;
    }

    // -------------------------------------------------------------------------
    // UserStorageProvider lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        // nothing to close — we hold no resources
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the Keycloak-local JPA user store, bypassing the federation
     * chain. Must be used for all direct DB operations inside a storage
     * provider to avoid infinite recursion through the full UserManager.
     */
    private UserProvider localStorage() {
        return UserStoragePrivateUtil.userLocalStorage(session);
    }
}
