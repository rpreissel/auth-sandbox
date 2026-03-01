package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.dto.AdminDeviceResponse;
import dev.authsandbox.authservice.entity.Device;
import dev.authsandbox.authservice.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceManagementServiceTest {

    @Mock private DeviceRepository deviceRepository;
    @Mock private KeycloakAdminClient keycloakAdminClient;

    private DeviceManagementService deviceManagementService;

    @BeforeEach
    void setUp() {
        deviceManagementService = new DeviceManagementService(deviceRepository, keycloakAdminClient);
    }

    @Test
    void listDevices_returnsAllEntries() {
        Device d1 = Device.builder().deviceId("dev-1").userId("u1").name("Device 1").build();
        Device d2 = Device.builder().deviceId("dev-2").userId("u2").name("Device 2").build();
        when(deviceRepository.findAll()).thenReturn(List.of(d1, d2));

        List<AdminDeviceResponse> result = deviceManagementService.listDevices();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AdminDeviceResponse::deviceId)
                .containsExactly("dev-1", "dev-2");
    }

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

        deviceManagementService.deleteDevice(id);

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

        deviceManagementService.deleteDevice(id);

        verify(deviceRepository).delete(device);
    }

    @Test
    void deleteDevice_throwsNoSuchElement_forUnknownId() {
        UUID id = UUID.randomUUID();
        when(deviceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceManagementService.deleteDevice(id))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void deleteDevice_deletesDeviceEvenWithoutKeycloakUserId() {
        UUID id = UUID.randomUUID();
        Device device = Device.builder()
                .deviceId("dev-003")
                .userId("user-003")
                .name("Old Device")
                .publicKey("pem")
                .keycloakUserId(null)
                .build();
        when(deviceRepository.findById(id)).thenReturn(Optional.of(device));

        deviceManagementService.deleteDevice(id);

        verify(keycloakAdminClient, never()).deleteUser(any());
        verify(deviceRepository).delete(device);
    }
}
