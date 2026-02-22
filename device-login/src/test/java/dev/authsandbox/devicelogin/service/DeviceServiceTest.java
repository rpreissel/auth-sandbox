package dev.authsandbox.devicelogin.service;

import dev.authsandbox.devicelogin.dto.RegisterDeviceRequest;
import dev.authsandbox.devicelogin.dto.RegisterDeviceResponse;
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
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        deviceService = new DeviceService(deviceRepository, registrationCodeRepository, keycloakAdminClient, passwordEncoder);
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    void registerDevice_succeedsAndIncrementsUseCount() {
        String plainCode = "secret";
        RegistrationCode regCode = activeCode("alice", "Alice Smith", plainCode, 0);
        when(registrationCodeRepository.findByUserId("alice")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByDeviceId("dev-001")).thenReturn(false);
        when(keycloakAdminClient.createUserWithFederatedIdentity("alice")).thenReturn("kc-uuid-alice");
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterDeviceResponse response = deviceService.registerDevice(
                new RegisterDeviceRequest("dev-001", "alice", "Alice Smith", plainCode, "pubkey"));

        assertThat(response.deviceId()).isEqualTo("dev-001");

        ArgumentCaptor<RegistrationCode> captor = ArgumentCaptor.forClass(RegistrationCode.class);
        verify(registrationCodeRepository).save(captor.capture());
        assertThat(captor.getValue().getUseCount()).isEqualTo(1);
    }

    @Test
    void registerDevice_allowsMultipleUsesBeforeExpiry() {
        String plainCode = "multi-secret";
        RegistrationCode regCode = activeCode("bob", "Bob", plainCode, 3);
        when(registrationCodeRepository.findByUserId("bob")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByDeviceId("dev-bob-4")).thenReturn(false);
        when(keycloakAdminClient.createUserWithFederatedIdentity("bob")).thenReturn("kc-uuid-bob");
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceService.registerDevice(
                new RegisterDeviceRequest("dev-bob-4", "bob", "Bob", plainCode, "pubkey"));

        ArgumentCaptor<RegistrationCode> captor = ArgumentCaptor.forClass(RegistrationCode.class);
        verify(registrationCodeRepository).save(captor.capture());
        assertThat(captor.getValue().getUseCount()).isEqualTo(4);
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
                .activationCode(passwordEncoder.encode(plainCode))
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .useCount(useCount)
                .build();
    }

    private RegistrationCode expiredCode(String userId, String name, String plainCode) {
        return RegistrationCode.builder()
                .userId(userId)
                .name(name)
                .activationCode(passwordEncoder.encode(plainCode))
                .expiresAt(OffsetDateTime.now().minusSeconds(1))
                .useCount(0)
                .build();
    }
}
