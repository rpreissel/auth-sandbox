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

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
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

    private static KeyPair RSA_KEY_PAIR;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            RSA_KEY_PAIR = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

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
        RegistrationCode regCode = activeCode("alice", plainCode, 0);
        when(registrationCodeRepository.findByUserId("alice")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByUserIdAndDeviceName("alice", "Pixel 8")).thenReturn(false);
        when(keycloakAdminClient.getUserIdByUsername("alice")).thenReturn(Optional.of("kc-uuid-alice"));
        doNothing().when(keycloakAdminClient).createDeviceCredential(any(), any(), any(), any(), any(), any());
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterDeviceResponse response = deviceService.registerDevice(
                new RegisterDeviceRequest("alice", "Pixel 8", plainCode, pemPublicKey()));

        assertThat(response.deviceName()).isEqualTo("Pixel 8");

        verify(keycloakAdminClient, never()).createUser(any(), any());
        verify(keycloakAdminClient).createDeviceCredential(any(), any(), any(), any(), any(), any());

        ArgumentCaptor<RegistrationCode> captor = ArgumentCaptor.forClass(RegistrationCode.class);
        verify(registrationCodeRepository).save(captor.capture());
        assertThat(captor.getValue().getUseCount()).isEqualTo(1);
    }

    @Test
    void registerDevice_createsKeycloakUser_whenUserWasDeletedFromKeycloak() {
        RegistrationCode regCode = activeCode("deleted-user", "secret", 0);
        when(registrationCodeRepository.findByUserId("deleted-user")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByUserIdAndDeviceName("deleted-user", "Pixel 9")).thenReturn(false);
        when(keycloakAdminClient.getUserIdByUsername("deleted-user")).thenReturn(Optional.empty());
        when(keycloakAdminClient.createUser("deleted-user", "Pixel 9")).thenReturn("kc-uuid-new");
        doNothing().when(keycloakAdminClient).createDeviceCredential(any(), any(), any(), any(), any(), any());
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterDeviceResponse response = deviceService.registerDevice(
                new RegisterDeviceRequest("deleted-user", "Pixel 9", "secret", pemPublicKey()));

        assertThat(response.deviceName()).isEqualTo("Pixel 9");
        verify(keycloakAdminClient).createUser("deleted-user", "Pixel 9");
        verify(keycloakAdminClient).createDeviceCredential(any(), any(), any(), any(), any(), any());
    }

    @Test
    void registerDevice_allowsMultipleUsesBeforeExpiry() {
        String plainCode = "multi-secret";
        RegistrationCode regCode = activeCode("bob", plainCode, 3);
        when(registrationCodeRepository.findByUserId("bob")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByUserIdAndDeviceName("bob", "MacBook")).thenReturn(false);
        when(keycloakAdminClient.getUserIdByUsername("bob")).thenReturn(Optional.of("kc-uuid-bob"));
        doNothing().when(keycloakAdminClient).createDeviceCredential(any(), any(), any(), any(), any(), any());
        when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceService.registerDevice(
                new RegisterDeviceRequest("bob", "MacBook", plainCode, pemPublicKey()));

        verify(keycloakAdminClient).createDeviceCredential(any(), any(), any(), any(), any(), any());

        ArgumentCaptor<RegistrationCode> captor = ArgumentCaptor.forClass(RegistrationCode.class);
        verify(registrationCodeRepository).save(captor.capture());
        assertThat(captor.getValue().getUseCount()).isEqualTo(4);
    }

    // -----------------------------------------------------------------------
    // Expiry checks
    // -----------------------------------------------------------------------

    @Test
    void registerDevice_throwsGenericError_whenCodeIsExpired() {
        RegistrationCode expired = expiredCode("charlie", "secret");
        when(registrationCodeRepository.findByUserId("charlie")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() ->
                deviceService.registerDevice(
                        new RegisterDeviceRequest("charlie", "Pixel", "secret", pemPublicKey())))
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
                        new RegisterDeviceRequest("nobody", "Pixel", "secret", pemPublicKey())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown userId");
    }

    // -----------------------------------------------------------------------
    // Validation checks
    // -----------------------------------------------------------------------

    @Test
    void registerDevice_throwsSecurityException_whenActivationCodeIsWrong() {
        RegistrationCode regCode = activeCode("eve", "correct-secret", 0);
        when(registrationCodeRepository.findByUserId("eve")).thenReturn(Optional.of(regCode));

        assertThatThrownBy(() ->
                deviceService.registerDevice(
                        new RegisterDeviceRequest("eve", "Pixel", "wrong-secret", pemPublicKey())))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void registerDevice_throwsIllegalArgument_whenDeviceNameAlreadyExists() {
        RegistrationCode regCode = activeCode("frank", "secret", 0);
        when(registrationCodeRepository.findByUserId("frank")).thenReturn(Optional.of(regCode));
        when(deviceRepository.existsByUserIdAndDeviceName("frank", "My Device")).thenReturn(true);

        assertThatThrownBy(() ->
                deviceService.registerDevice(
                        new RegisterDeviceRequest("frank", "My Device", "secret", pemPublicKey())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String pemPublicKey() {
        byte[] encoded = RSA_KEY_PAIR.getPublic().getEncoded();
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----";
    }

    private RegistrationCode activeCode(String userId, String plainCode, int useCount) {
        return RegistrationCode.builder()
                .userId(userId)
                .activationCode(plainCode)
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .useCount(useCount)
                .build();
    }

    private RegistrationCode expiredCode(String userId, String plainCode) {
        return RegistrationCode.builder()
                .userId(userId)
                .activationCode(plainCode)
                .expiresAt(OffsetDateTime.now().minusSeconds(1))
                .useCount(0)
                .build();
    }
}
