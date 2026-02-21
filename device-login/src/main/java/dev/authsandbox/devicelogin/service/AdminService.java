package dev.authsandbox.devicelogin.service;

import dev.authsandbox.devicelogin.dto.AdminDeviceResponse;
import dev.authsandbox.devicelogin.dto.AdminRegistrationCodeResponse;
import dev.authsandbox.devicelogin.dto.CreateRegistrationCodeRequest;
import dev.authsandbox.devicelogin.entity.Device;
import dev.authsandbox.devicelogin.entity.RegistrationCode;
import dev.authsandbox.devicelogin.repository.DeviceRepository;
import dev.authsandbox.devicelogin.repository.RegistrationCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

    private final RegistrationCodeRepository registrationCodeRepository;
    private final DeviceRepository deviceRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final PasswordEncoder passwordEncoder;

    // -----------------------------------------------------------------------
    // Registration codes
    // -----------------------------------------------------------------------

    public List<AdminRegistrationCodeResponse> listRegistrationCodes() {
        return registrationCodeRepository.findAll().stream()
                .map(this::toRegistrationCodeResponse)
                .toList();
    }

    @Transactional
    @SuppressWarnings("null")
    public AdminRegistrationCodeResponse createRegistrationCode(CreateRegistrationCodeRequest request) {
        // Reject duplicate userId to maintain the unique constraint semantics.
        if (registrationCodeRepository.findByUserId(request.userId()).isPresent()) {
            throw new IllegalArgumentException("A registration code for userId '" + request.userId() + "' already exists");
        }

        String hashedCode = passwordEncoder.encode(request.activationCode());

        RegistrationCode entity = RegistrationCode.builder()
                .userId(request.userId())
                .name(request.name())
                .activationCode(hashedCode)
                .build();

        RegistrationCode saved = registrationCodeRepository.save(entity);
        log.info("Created registration code id='{}' for userId='{}'", saved.getId(), saved.getUserId());
        return toRegistrationCodeResponse(saved);
    }

    @Transactional
    @SuppressWarnings("null")
    public void deleteRegistrationCode(UUID id) {
        RegistrationCode code = registrationCodeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Registration code not found: " + id));
        registrationCodeRepository.delete(code);
        log.info("Deleted registration code id='{}'", id);
    }

    // -----------------------------------------------------------------------
    // Devices
    // -----------------------------------------------------------------------

    public List<AdminDeviceResponse> listDevices() {
        return deviceRepository.findAll().stream()
                .map(this::toDeviceResponse)
                .toList();
    }

    @Transactional
    @SuppressWarnings("null")
    public void deleteDevice(UUID id) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Device not found: " + id));

        // Remove the corresponding Keycloak user if one was created.
        if (device.getKeycloakUserId() != null) {
            try {
                keycloakAdminClient.deleteUser(device.getKeycloakUserId());
            } catch (Exception ex) {
                // Log and continue — the device row should still be removed even if
                // Keycloak cleanup fails (e.g. user was already deleted manually).
                log.warn("Failed to delete Keycloak user '{}' for device '{}': {}",
                        device.getKeycloakUserId(), device.getDeviceId(), ex.getMessage());
            }
        }

        deviceRepository.delete(device);
        log.info("Deleted device id='{}' deviceId='{}'", id, device.getDeviceId());
    }

    // -----------------------------------------------------------------------
    // Mapping helpers
    // -----------------------------------------------------------------------

    private AdminRegistrationCodeResponse toRegistrationCodeResponse(RegistrationCode rc) {
        return new AdminRegistrationCodeResponse(
                rc.getId(),
                rc.getUserId(),
                rc.getName(),
                rc.isUsed(),
                rc.getCreatedAt(),
                rc.getUsedAt()
        );
    }

    private AdminDeviceResponse toDeviceResponse(Device d) {
        return new AdminDeviceResponse(
                d.getId(),
                d.getDeviceId(),
                d.getUserId(),
                d.getName(),
                d.getKeycloakUserId(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }
}
