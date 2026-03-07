package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.dto.RegisterDeviceRequest;
import dev.authsandbox.authservice.dto.RegisterDeviceResponse;
import dev.authsandbox.authservice.entity.RegistrationCode;
import dev.authsandbox.authservice.repository.DeviceRepository;
import dev.authsandbox.authservice.repository.RegistrationCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock private DeviceRepository deviceRepository;
    @Mock private RegistrationCodeRepository registrationCodeRepository;
    @Mock private KeycloakAdminClient keycloakAdminClient;

    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        deviceService = new DeviceService(deviceRepository, registrationCodeRepository, keycloakAdminClient);
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    void registerDevice_reusesPreCreatedKeycloakUser_andIncrementsUseCount() {
        String plainCode = "secret";
        RegistrationCode regCode = activeCode("alice", "Alice Smith", plainCode, 0);
        when(registrationCodeRepository.findByUserId("alice")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByDeviceId("dev-001")).thenReturn(false);
        when(keycloakAdminClient.getUserIdByUsername("alice")).thenReturn(Optional.of("kc-uuid-alice"));
        when(keycloakAdminClient.hasPassword("kc-uuid-alice")).thenReturn(true);
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterDeviceResponse response = deviceService.registerDevice(
                new RegisterDeviceRequest("dev-001", "alice", "Alice Smith", plainCode, "pubkey"));

        assertThat(response.deviceId()).isEqualTo("dev-001");

        // The pre-created Keycloak user must be reused — no new user should be created.
        verify(keycloakAdminClient, never()).createUserWithFederatedIdentity(any(), any());

        // The device-login federated identity must always be ensured — this is the
        // fix for: Keycloak reports "No user found for federated identity" when a
        // pre-provisioned user (created by AdminService) registers a device.
        verify(keycloakAdminClient).ensureDeviceLoginFederatedIdentityLink("alice");

        ArgumentCaptor<RegistrationCode> captor = ArgumentCaptor.forClass(RegistrationCode.class);
        verify(registrationCodeRepository).save(captor.capture());
        assertThat(captor.getValue().getUseCount()).isEqualTo(1);
    }

    @Test
    void registerDevice_ensuresFederatedIdentityLink_whenUserPreExistsInKeycloak() {
        // Regression test for: Biometric login fails with HTTP 500 because Keycloak
        // reports "No user found for federated identity sub='...' idp=device-login-idp".
        // Root cause: pre-provisioned users (created by AdminService) are in Keycloak
        // but have no federated identity link for device-login-idp. Device registration
        // must always call ensureDeviceLoginFederatedIdentityLink regardless of whether
        // the user already existed.
        RegistrationCode regCode = activeCode("pre-existing-user", "Pre Existing", "secret", 0);
        when(registrationCodeRepository.findByUserId("pre-existing-user")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByDeviceId("dev-pe")).thenReturn(false);
        when(keycloakAdminClient.getUserIdByUsername("pre-existing-user")).thenReturn(Optional.of("kc-uuid-pe"));
        when(keycloakAdminClient.hasPassword("kc-uuid-pe")).thenReturn(true);
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceService.registerDevice(
                new RegisterDeviceRequest("dev-pe", "pre-existing-user", "Pre Existing", "secret", "pubkey"));

        verify(keycloakAdminClient, never()).createUserWithFederatedIdentity(any(), any());
        verify(keycloakAdminClient).ensureDeviceLoginFederatedIdentityLink("pre-existing-user");
    }

    @Test
    void registerDevice_allowsMultipleUsesBeforeExpiry() {
        String plainCode = "multi-secret";
        RegistrationCode regCode = activeCode("bob", "Bob", plainCode, 3);
        when(registrationCodeRepository.findByUserId("bob")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByDeviceId("dev-bob-4")).thenReturn(false);
        when(keycloakAdminClient.getUserIdByUsername("bob")).thenReturn(Optional.of("kc-uuid-bob"));
        when(keycloakAdminClient.hasPassword("kc-uuid-bob")).thenReturn(true);
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceService.registerDevice(
                new RegisterDeviceRequest("dev-bob-4", "bob", "Bob", plainCode, "pubkey"));

        verify(keycloakAdminClient, never()).createUserWithFederatedIdentity(any(), any());
        verify(keycloakAdminClient).ensureDeviceLoginFederatedIdentityLink("bob");

        ArgumentCaptor<RegistrationCode> captor = ArgumentCaptor.forClass(RegistrationCode.class);
        verify(registrationCodeRepository).save(captor.capture());
        assertThat(captor.getValue().getUseCount()).isEqualTo(4);
    }

    @Test
    void registerDevice_createsKeycloakUser_whenUserWasDeletedFromKeycloak() {
        // The Keycloak user may have been manually deleted since provisioning.
        // The service must recreate it on demand (createUserWithFederatedIdentity already
        // sets up the federated identity link, but ensureDeviceLoginFederatedIdentityLink
        // is still called as a safety net afterwards).
        RegistrationCode regCode = activeCode("deleted-user", "Deleted User", "secret", 0);
        when(registrationCodeRepository.findByUserId("deleted-user")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByDeviceId("dev-deleted")).thenReturn(false);
        when(keycloakAdminClient.getUserIdByUsername("deleted-user")).thenReturn(Optional.empty());
        when(keycloakAdminClient.createUserWithFederatedIdentity("deleted-user", "Deleted User"))
                .thenReturn("kc-uuid-new");
        when(keycloakAdminClient.hasPassword("kc-uuid-new")).thenReturn(true);
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterDeviceResponse response = deviceService.registerDevice(
                new RegisterDeviceRequest("dev-deleted", "deleted-user", "Deleted User", "secret", "pubkey"));

        assertThat(response.deviceId()).isEqualTo("dev-deleted");
        verify(keycloakAdminClient).createUserWithFederatedIdentity("deleted-user", "Deleted User");
        verify(keycloakAdminClient).ensureDeviceLoginFederatedIdentityLink("deleted-user");
    }

    @Test
    void registerDevice_addsUpdatePasswordRequiredAction_whenUserHasNoPassword() {
        RegistrationCode regCode = activeCode("alice", "Alice Smith", "secret", 0);
        when(registrationCodeRepository.findByUserId("alice")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByDeviceId("dev-001")).thenReturn(false);
        when(keycloakAdminClient.getUserIdByUsername("alice")).thenReturn(Optional.of("kc-uuid-alice"));
        when(keycloakAdminClient.hasPassword("kc-uuid-alice")).thenReturn(false);
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceService.registerDevice(
                new RegisterDeviceRequest("dev-001", "alice", "Alice Smith", "secret", "pubkey"));

        verify(keycloakAdminClient).addRequiredAction("kc-uuid-alice", "UPDATE_PASSWORD");
    }

    @Test
    void registerDevice_doesNotAddUpdatePasswordAction_whenUserAlreadyHasPassword() {
        RegistrationCode regCode = activeCode("bob", "Bob", "secret", 0);
        when(registrationCodeRepository.findByUserId("bob")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByDeviceId("dev-bob")).thenReturn(false);
        when(keycloakAdminClient.getUserIdByUsername("bob")).thenReturn(Optional.of("kc-uuid-bob"));
        when(keycloakAdminClient.hasPassword("kc-uuid-bob")).thenReturn(true);
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceService.registerDevice(
                new RegisterDeviceRequest("dev-bob", "bob", "Bob", "secret", "pubkey"));

        verify(keycloakAdminClient, never()).addRequiredAction(any(), any());
    }

    // -----------------------------------------------------------------------
    // Expiry checks
    // -----------------------------------------------------------------------

    @Test
    void registerDevice_throwsGenericError_whenCodeIsExpired() {
        RegistrationCode expired = expiredCode("charlie", "Charlie", "secret");
        when(registrationCodeRepository.findByUserId("charlie")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() ->
                deviceService.registerDevice(
                        new RegisterDeviceRequest("dev-x", "charlie", "Charlie", "secret", "pubkey")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown userId");

        verify(deviceRepository, never()).save(any());
        verify(registrationCodeRepository, never()).save(any());
    }

    @Test
    void registerDevice_throwsGenericError_whenUserIdUnknown() {
        when(registrationCodeRepository.findByUserId("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                deviceService.registerDevice(
                        new RegisterDeviceRequest("dev-x", "nobody", "Nobody", "secret", "pubkey")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown userId");
    }

    // -----------------------------------------------------------------------
    // Validation checks
    // -----------------------------------------------------------------------

    @Test
    void registerDevice_throwsIllegalArgument_whenNameDoesNotMatch() {
        RegistrationCode regCode = activeCode("dave", "Dave", "secret", 0);
        when(registrationCodeRepository.findByUserId("dave")).thenReturn(Optional.of(regCode));

        assertThatThrownBy(() ->
                deviceService.registerDevice(
                        new RegisterDeviceRequest("dev-dave", "dave", "Wrong Name", "secret", "pubkey")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name");
    }

    @Test
    void registerDevice_throwsSecurityException_whenActivationCodeIsWrong() {
        RegistrationCode regCode = activeCode("eve", "Eve", "correct-secret", 0);
        when(registrationCodeRepository.findByUserId("eve")).thenReturn(Optional.of(regCode));

        assertThatThrownBy(() ->
                deviceService.registerDevice(
                        new RegisterDeviceRequest("dev-eve", "eve", "Eve", "wrong-secret", "pubkey")))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void registerDevice_throwsIllegalArgument_whenDeviceAlreadyRegistered() {
        RegistrationCode regCode = activeCode("frank", "Frank", "secret", 0);
        when(registrationCodeRepository.findByUserId("frank")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByDeviceId("dev-frank")).thenReturn(true);

        assertThatThrownBy(() ->
                deviceService.registerDevice(
                        new RegisterDeviceRequest("dev-frank", "frank", "Frank", "secret", "pubkey")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private RegistrationCode activeCode(String userId, String name, String plainCode, int useCount) {
        return RegistrationCode.builder()
                .userId(userId)
                .name(name)
                .activationCode(plainCode)
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .useCount(useCount)
                .build();
    }

    private RegistrationCode expiredCode(String userId, String name, String plainCode) {
        return RegistrationCode.builder()
                .userId(userId)
                .name(name)
                .activationCode(plainCode)
                .expiresAt(OffsetDateTime.now().minusSeconds(1))
                .useCount(0)
                .build();
    }
}
