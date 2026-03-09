package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.dto.AdminRegistrationCodeResponse;
import dev.authsandbox.authservice.dto.CleanupResult;
import dev.authsandbox.authservice.dto.CreateRegistrationCodeRequest;
import dev.authsandbox.authservice.dto.SyncResult;
import dev.authsandbox.authservice.entity.RegistrationCode;
import dev.authsandbox.authservice.repository.RegistrationCodeRepository;
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
public class RegistrationCodeService {

    static final int DEFAULT_VALID_FOR_DAYS = 90;

    private final RegistrationCodeRepository registrationCodeRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    public List<AdminRegistrationCodeResponse> listRegistrationCodes() {
        return registrationCodeRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    @SuppressWarnings("null")
    public AdminRegistrationCodeResponse createRegistrationCode(CreateRegistrationCodeRequest request) {
        if (registrationCodeRepository.findByUserId(request.userId()).isPresent()) {
            throw new IllegalArgumentException("A registration code for userId '" + request.userId() + "' already exists");
        }

        if (keycloakAdminClient.getUserIdByUsername(request.userId()).isEmpty()) {
            keycloakAdminClient.createUser(request.userId(), request.name());
        } else {
            log.debug("Keycloak user already exists for userId='{}', skipping creation", request.userId());
        }

        int validForDays = request.validForDays() != null ? request.validForDays() : DEFAULT_VALID_FOR_DAYS;
        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(validForDays);

        RegistrationCode entity = RegistrationCode.builder()
                .userId(request.userId())
                .name(request.name())
                .activationCode(request.activationCode())
                .expiresAt(expiresAt)
                .build();

        RegistrationCode saved = registrationCodeRepository.save(entity);
        log.info("Created registration code id='{}' for userId='{}', expires at {}",
                saved.getId(), saved.getUserId(), saved.getExpiresAt());
        return toResponse(saved);
    }

    @Transactional
    @SuppressWarnings("null")
    public void deleteRegistrationCode(UUID id) {
        RegistrationCode code = registrationCodeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Registration code not found: " + id));

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
                    keycloakAdminClient.createUser(code.getUserId(), code.getName());
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

    private AdminRegistrationCodeResponse toResponse(RegistrationCode rc) {
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

    @Transactional
    public CleanupResult deleteExpiredCodes() {
        List<RegistrationCode> codes = registrationCodeRepository.findAll();
        int deleted = 0;
        int skipped = 0;

        for (RegistrationCode code : codes) {
            if (code.isExpired()) {
                if (code.getUseCount() == 0) {
                    try {
                        keycloakAdminClient.deleteUserByUsername(code.getUserId());
                        registrationCodeRepository.delete(code);
                        deleted++;
                        log.info("Deleted expired registration code id='{}' for userId='{}'", 
                                code.getId(), code.getUserId());
                    } catch (Exception ex) {
                        log.warn("Failed to delete expired code for userId '{}': {}", 
                                code.getUserId(), ex.getMessage());
                    }
                } else {
                    skipped++;
                    log.debug("Skipped expired code id='{}' for userId='{}' - has {} registered devices", 
                            code.getId(), code.getUserId(), code.getUseCount());
                }
            }
        }

        log.info("Cleanup complete - deleted={}, skipped={}", deleted, skipped);
        return new CleanupResult(deleted, skipped);
    }
}
