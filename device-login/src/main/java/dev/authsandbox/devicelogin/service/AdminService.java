package dev.authsandbox.devicelogin.service;

import dev.authsandbox.devicelogin.dto.AdminDeviceResponse;
import dev.authsandbox.devicelogin.dto.AdminRegistrationCodeResponse;
import dev.authsandbox.devicelogin.dto.CreateRegistrationCodeRequest;
import dev.authsandbox.devicelogin.dto.SyncResult;
import dev.authsandbox.devicelogin.entity.Device;
import dev.authsandbox.devicelogin.entity.RegistrationCode;
import dev.authsandbox.devicelogin.repository.DeviceRepository;
import dev.authsandbox.devicelogin.repository.RegistrationCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

    /** Default validity window when {@code validForHours} is not specified in the request. */
    static final int DEFAULT_VALID_FOR_HOURS = 24;

    private final RegistrationCodeRepository registrationCodeRepository;
    private final DeviceRepository deviceRepository;
    private final KeycloakAdminClient keycloakAdminClient;

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

        // Create the Keycloak user immediately so it is ready when the device registers.
        // We do not persist the Keycloak UUID — existence is always verified by username lookup.
        keycloakAdminClient.createUserWithFederatedIdentity(request.userId(), request.name());

        int validForHours = request.validForHours() != null ? request.validForHours() : DEFAULT_VALID_FOR_HOURS;
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(validForHours);

        RegistrationCode entity = RegistrationCode.builder()
                .userId(request.userId())
                .name(request.name())
                .activationCode(request.activationCode())
                .expiresAt(expiresAt)
                .build();

        RegistrationCode saved = registrationCodeRepository.save(entity);
        log.info("Created registration code id='{}' for userId='{}', expires at {}",
                saved.getId(), saved.getUserId(), saved.getExpiresAt());
        return toRegistrationCodeResponse(saved);
    }

    @Transactional
    @SuppressWarnings("null")
    public void deleteRegistrationCode(UUID id) {
        RegistrationCode code = registrationCodeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Registration code not found: " + id));

        // Remove the pre-created Keycloak user if no device has been registered yet.
        // Once a device is registered the user is owned by the Device row and must
        // be cleaned up via deleteDevice instead.
        if (code.getUseCount() == 0) {
            try {
                keycloakAdminClient.deleteUserByUsername(code.getUserId());
            } catch (Exception ex) {
                log.warn("Failed to delete Keycloak user for userId '{}' (registration code '{}'): {}",
                        code.getUserId(), id, ex.getMessage());
            }
        }

        registrationCodeRepository.delete(code);
        log.info("Deleted registration code id='{}'", id);
    }

    /**
     * Ensures every registration code has a corresponding Keycloak user with a
     * federated identity link. Checks by username whether the user already exists;
     * creates it if missing. Failures for individual codes are logged but do not
     * abort the operation — the returned {@link SyncResult} reports the full outcome.
     */
    @Transactional
    public SyncResult syncKeycloakUsers() {
        List<RegistrationCode> codes = registrationCodeRepository.findAll();
        int synced = 0;
        int alreadySynced = 0;
        int failed = 0;

        for (RegistrationCode code : codes) {
            try {
                boolean exists = keycloakAdminClient.getUserIdByUsername(code.getUserId()).isPresent();
                if (exists) {
                    alreadySynced++;
                    log.debug("Sync: Keycloak user already exists for userId='{}'", code.getUserId());
                } else {
                    keycloakAdminClient.createUserWithFederatedIdentity(code.getUserId(), code.getName());
                    synced++;
                    log.info("Sync: created Keycloak user for userId='{}'", code.getUserId());
                }
            } catch (Exception ex) {
                failed++;
                log.warn("Sync: failed for userId='{}': {}", code.getUserId(), ex.getMessage());
            }
        }

        log.info("Keycloak user sync complete — synced={}, alreadySynced={}, failed={}", synced, alreadySynced, failed);
        return new SyncResult(synced, alreadySynced, failed);
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
                rc.getActivationCode(),
                rc.getExpiresAt(),
                rc.getUseCount(),
                rc.getCreatedAt()
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
