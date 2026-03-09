package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.dto.RegisterDeviceRequest;
import dev.authsandbox.authservice.dto.RegisterDeviceResponse;
import dev.authsandbox.authservice.entity.Device;
import dev.authsandbox.authservice.entity.RegistrationCode;
import dev.authsandbox.authservice.repository.DeviceRepository;
import dev.authsandbox.authservice.repository.RegistrationCodeRepository;
import dev.authsandbox.authservice.security.KeyLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HexFormat;

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
        if (regCode.isExpired()) {
            throw new IllegalArgumentException("Unknown userId");
        }

        // --- 3. Verify the activation code -------------------------------------
        if (!regCode.getActivationCode().equals(request.activationCode())) {
            log.warn("Invalid activation code supplied for userId '{}'", request.userId());
            throw new SecurityException("Invalid activation code");
        }

        // --- 4. Guard against duplicate device name for this user ---------------
        if (deviceRepository.existsByUserIdAndDeviceName(request.userId(), request.deviceName())) {
            throw new IllegalArgumentException("Device name already exists for this user");
        }

        // --- 5. Calculate publicKeyHash -----------------------------------------
        PublicKey publicKey = KeyLoader.parsePublicKey(request.publicKey());
        String publicKeyHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));

        // --- 6. Generate RSA-2048 encryption keypair ---------------------------
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair encKeyPair = gen.generateKeyPair();
            String encPubKey = Base64.getEncoder().encodeToString(encKeyPair.getPublic().getEncoded());
            String encPrivKey = Base64.getEncoder().encodeToString(encKeyPair.getPrivate().getEncoded());

            // --- 7. Resolve the Keycloak user ----------------------------------
            String keycloakUserId = keycloakAdminClient.getUserIdByUsername(request.userId())
                    .orElseGet(() -> {
                        log.warn("Keycloak user for userId '{}' not found at device-registration time; creating now.",
                                request.userId());
                        return keycloakAdminClient.createUser(request.userId(), request.deviceName());
                    });

            // --- 8. Create Keycloak credential with deviceName -----------------
            keycloakAdminClient.createDeviceCredential(
                    keycloakUserId,
                    request.publicKey(),
                    publicKeyHash,
                    request.deviceName(),
                    encPubKey,
                    encPrivKey
            );

            // --- 9. Persist the device -----------------------------------------
            Device device = Device.builder()
                    .userId(request.userId())
                    .deviceName(request.deviceName())
                    .publicKey(request.publicKey())
                    .publicKeyHash(publicKeyHash)
                    .encPubKey(encPubKey)
                    .keycloakUserId(keycloakUserId)
                    .build();
            deviceRepository.save(device);

            // --- 10. Increment the use counter on the registration code --------
            regCode.setUseCount(regCode.getUseCount() + 1);
            registrationCodeRepository.save(regCode);

            log.info("Registered device '{}' for userId '{}' (keycloak user: {}, code use count: {})",
                    request.deviceName(), request.userId(), keycloakUserId, regCode.getUseCount());

            return new RegisterDeviceResponse("Device registered successfully", request.deviceName());

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate encryption keys", e);
        }
    }
}
