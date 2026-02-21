package dev.authsandbox.devicelogin.service;

import dev.authsandbox.devicelogin.dto.AdminRegistrationCodeResponse;
import dev.authsandbox.devicelogin.dto.CreateRegistrationCodeRequest;
import dev.authsandbox.devicelogin.entity.Device;
import dev.authsandbox.devicelogin.entity.RegistrationCode;
import dev.authsandbox.devicelogin.repository.DeviceRepository;
import dev.authsandbox.devicelogin.repository.RegistrationCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock private RegistrationCodeRepository registrationCodeRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private KeycloakAdminClient keycloakAdminClient;

    private AdminService adminService;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        // Use strength 4 in tests to keep BCrypt fast.
        passwordEncoder = new BCryptPasswordEncoder(4);
        adminService = new AdminService(registrationCodeRepository, deviceRepository, keycloakAdminClient, passwordEncoder);
    }

    // -----------------------------------------------------------------------
    // createRegistrationCode
    // -----------------------------------------------------------------------

    @Test
    void createRegistrationCode_persistsHashedActivationCode() {
        when(registrationCodeRepository.findByUserId("alice")).thenReturn(Optional.empty());
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        adminService.createRegistrationCode(
                new CreateRegistrationCodeRequest("alice", "Alice Smith", "plain-secret"));

        ArgumentCaptor<RegistrationCode> captor = ArgumentCaptor.forClass(RegistrationCode.class);
        verify(registrationCodeRepository).save(captor.capture());

        RegistrationCode saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("alice");
        assertThat(saved.getName()).isEqualTo("Alice Smith");
        // The stored value must be a BCrypt hash, not the plain-text secret.
        assertThat(saved.getActivationCode()).startsWith("$2");
        assertThat(passwordEncoder.matches("plain-secret", saved.getActivationCode())).isTrue();
    }

    @Test
    void createRegistrationCode_throwsIllegalArgument_forDuplicateUserId() {
        RegistrationCode existing = RegistrationCode.builder()
                .userId("alice")
                .name("Alice")
                .activationCode("$2a$hash")
                .build();
        when(registrationCodeRepository.findByUserId("alice")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->
                adminService.createRegistrationCode(
                        new CreateRegistrationCodeRequest("alice", "Alice", "secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alice");
    }

    // -----------------------------------------------------------------------
    // deleteRegistrationCode
    // -----------------------------------------------------------------------

    @Test
    void deleteRegistrationCode_deletesExistingEntry() {
        UUID id = UUID.randomUUID();
        RegistrationCode code = RegistrationCode.builder()
                .userId("bob")
                .name("Bob")
                .activationCode("$2a$hash")
                .build();
        when(registrationCodeRepository.findById(id)).thenReturn(Optional.of(code));

        adminService.deleteRegistrationCode(id);

        verify(registrationCodeRepository).delete(code);
    }

    @Test
    void deleteRegistrationCode_throwsNoSuchElement_forUnknownId() {
        UUID id = UUID.randomUUID();
        when(registrationCodeRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.deleteRegistrationCode(id))
                .isInstanceOf(NoSuchElementException.class);
    }

    // -----------------------------------------------------------------------
    // listRegistrationCodes
    // -----------------------------------------------------------------------

    @Test
    void listRegistrationCodes_returnsAllEntries() {
        RegistrationCode r1 = RegistrationCode.builder().userId("u1").name("User 1").activationCode("h1").build();
        RegistrationCode r2 = RegistrationCode.builder().userId("u2").name("User 2").activationCode("h2").build();
        when(registrationCodeRepository.findAll()).thenReturn(List.of(r1, r2));

        List<AdminRegistrationCodeResponse> result = adminService.listRegistrationCodes();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AdminRegistrationCodeResponse::userId)
                .containsExactly("u1", "u2");
    }

    // -----------------------------------------------------------------------
    // deleteDevice
    // -----------------------------------------------------------------------

    @Test
    void deleteDevice_deletesDeviceAndKeycloakUser() {
        UUID id = UUID.randomUUID();
        Device device = Device.builder()
                .deviceId("dev-001")
                .userId("user-001")
                .name("My Phone")
                .publicKey("pem")
                .keycloakUserId("kc-uuid-001")
                .build();
        when(deviceRepository.findById(id)).thenReturn(Optional.of(device));

        adminService.deleteDevice(id);

        verify(keycloakAdminClient).deleteUser("kc-uuid-001");
        verify(deviceRepository).delete(device);
    }

    @Test
    void deleteDevice_continuesIfKeycloakUserDeletionFails() {
        UUID id = UUID.randomUUID();
        Device device = Device.builder()
                .deviceId("dev-002")
                .userId("user-002")
                .name("Tablet")
                .publicKey("pem")
                .keycloakUserId("kc-uuid-002")
                .build();
        when(deviceRepository.findById(id)).thenReturn(Optional.of(device));
        doThrow(new RuntimeException("Keycloak unavailable")).when(keycloakAdminClient).deleteUser("kc-uuid-002");

        // Must not throw — Keycloak cleanup failures are logged and ignored.
        adminService.deleteDevice(id);

        verify(deviceRepository).delete(device);
    }

    @Test
    void deleteDevice_throwsNoSuchElement_forUnknownId() {
        UUID id = UUID.randomUUID();
        when(deviceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.deleteDevice(id))
                .isInstanceOf(NoSuchElementException.class);
    }
}
