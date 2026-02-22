package dev.authsandbox.devicelogin.service;

import dev.authsandbox.devicelogin.dto.RegisterDeviceRequest;
import dev.authsandbox.devicelogin.dto.RegisterDeviceResponse;
import dev.authsandbox.devicelogin.entity.Device;
import dev.authsandbox.devicelogin.entity.RegistrationCode;
import dev.authsandbox.devicelogin.repository.DeviceRepository;
import dev.authsandbox.devicelogin.repository.RegistrationCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final RegistrationCodeRepository registrationCodeRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    @Transactional
    @SuppressWarnings("null")
    public RegisterDeviceResponse registerDevice(RegisterDeviceRequest request) {

        // --- 1. Look up the pre-provisioned registration entry -----------------
        RegistrationCode regCode = registrationCodeRepository
                .findByUserId(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown userId"));

        // --- 2. Verify that the entry has not expired --------------------------
        // Return the same generic message as for an unknown userId to avoid
        // leaking information about whether the userId exists.
        if (regCode.isExpired()) {
            throw new IllegalArgumentException("Unknown userId");
        }

        // --- 3. Verify the provided name matches the pre-provisioned name ------
        if (!regCode.getName().equals(request.name())) {
            throw new IllegalArgumentException("Name does not match");
        }

        // --- 4. Verify the activation code -------------------------------------
        if (!regCode.getActivationCode().equals(request.activationCode())) {
            log.warn("Invalid activation code supplied for userId '{}'", request.userId());
            throw new SecurityException("Invalid activation code");
        }

        // --- 5. Guard against duplicate device registration --------------------
        if (deviceRepository.existsByDeviceId(request.deviceId())) {
            throw new IllegalArgumentException("Device already registered");
        }

        // --- 6. Resolve the Keycloak user ----------------------------------------
        // Look up by username; if the user was deleted since provisioning, create it.
        String keycloakUserId = keycloakAdminClient.getUserIdByUsername(request.userId())
                .orElseGet(() -> {
                    log.warn("Keycloak user for userId '{}' not found at device-registration time; creating now.",
                            request.userId());
                    return keycloakAdminClient.createUserWithFederatedIdentity(
                            request.userId(), regCode.getName());
                });

        // --- 7. Persist the device ---------------------------------------------
        Device device = Device.builder()
                .deviceId(request.deviceId())
                .userId(request.userId())
                .name(request.name())
                .publicKey(request.publicKey())
                .keycloakUserId(keycloakUserId)
                .build();
        deviceRepository.save(device);

        // --- 8. Increment the use counter on the registration code -------------
        regCode.setUseCount(regCode.getUseCount() + 1);
        registrationCodeRepository.save(regCode);

        log.info("Registered device '{}' for userId '{}' (keycloak user: {}, code use count: {})",
                request.deviceId(), request.userId(), keycloakUserId, regCode.getUseCount());

        return new RegisterDeviceResponse(request.deviceId(), "Device registered successfully");
    }
}
