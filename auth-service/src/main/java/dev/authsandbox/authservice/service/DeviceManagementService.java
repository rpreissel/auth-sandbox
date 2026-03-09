package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.dto.AdminDeviceResponse;
import dev.authsandbox.authservice.entity.Device;
import dev.authsandbox.authservice.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceManagementService {

    private final DeviceRepository deviceRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    public List<AdminDeviceResponse> listDevices() {
        return deviceRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    @SuppressWarnings("null")
    public void deleteDevice(UUID id) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Device not found: " + id));

        if (device.getKeycloakUserId() != null) {
            try {
                keycloakAdminClient.deleteUser(device.getKeycloakUserId());
            } catch (Exception ex) {
                log.warn("Failed to delete Keycloak user '{}' for device '{}': {}",
                        device.getKeycloakUserId(), device.getDeviceName(), ex.getMessage());
            }
        }

        deviceRepository.delete(device);
        log.info("Deleted device id='{}' deviceName='{}'", id, device.getDeviceName());
    }

    private AdminDeviceResponse toResponse(Device d) {
        return new AdminDeviceResponse(
                d.getId(),
                d.getUserId(),
                d.getDeviceName(),
                d.getPublicKeyHash(),
                d.getKeycloakUserId(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }
}
