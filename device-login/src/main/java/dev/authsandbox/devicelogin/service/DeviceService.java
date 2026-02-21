package dev.authsandbox.devicelogin.service;

import dev.authsandbox.devicelogin.dto.RegisterDeviceRequest;
import dev.authsandbox.devicelogin.dto.RegisterDeviceResponse;
import dev.authsandbox.devicelogin.entity.Device;
import dev.authsandbox.devicelogin.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    @Transactional
    public RegisterDeviceResponse registerDevice(RegisterDeviceRequest request) {
        if (deviceRepository.existsByDeviceId(request.deviceId())) {
            throw new IllegalArgumentException(
                    "Device already registered: " + request.deviceId());
        }

        String keycloakUserId = keycloakAdminClient.createUserWithFederatedIdentity(request.deviceId());

        Device device = Device.builder()
                .deviceId(request.deviceId())
                .publicKey(request.publicKey())
                .keycloakUserId(keycloakUserId)
                .build();

        deviceRepository.save(device);
        log.info("Registered new device '{}' (keycloak user: {})", request.deviceId(), keycloakUserId);

        return new RegisterDeviceResponse(request.deviceId(), "Device registered successfully");
    }
}
