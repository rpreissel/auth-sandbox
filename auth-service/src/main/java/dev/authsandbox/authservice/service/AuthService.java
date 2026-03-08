package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.config.ChallengeProperties;
import dev.authsandbox.authservice.dto.KeycloakTokenResponse;
import dev.authsandbox.authservice.dto.LoginResponse;
import dev.authsandbox.authservice.dto.StartLoginRequest;
import dev.authsandbox.authservice.dto.StartLoginResponse;
import dev.authsandbox.authservice.dto.VerifyChallengeRequest;
import dev.authsandbox.authservice.entity.Challenge;
import dev.authsandbox.authservice.entity.Device;
import dev.authsandbox.authservice.repository.ChallengeRepository;
import dev.authsandbox.authservice.repository.DeviceRepository;
import dev.authsandbox.authservice.security.KeyLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final DeviceRepository deviceRepository;
    private final ChallengeRepository challengeRepository;
    private final JwtService jwtService;
    private final KeycloakAuthClient keycloakAuthClient;
    private final KeycloakAdminClient keycloakAdminClient;
    private final ChallengeProperties challengeProperties;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String ACR_BIOMETRIC = "2";

    @Transactional
    @SuppressWarnings("null")
    public StartLoginResponse startLogin(StartLoginRequest request) {
        Device device = deviceRepository.findByDeviceId(request.deviceId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown device: " + request.deviceId()));

        byte[] challengeBytes = new byte[32];
        SECURE_RANDOM.nextBytes(challengeBytes);
        String challengeValue = HexFormat.of().formatHex(challengeBytes);

        byte[] nonceBytes = new byte[16];
        SECURE_RANDOM.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);

        OffsetDateTime expiresAt = OffsetDateTime.now()
                .plusSeconds(challengeProperties.expirationSeconds());

        Challenge challenge = Challenge.builder()
                .deviceId(device.getDeviceId())
                .challengeValue(challengeValue)
                .nonce(nonce)
                .expiresAt(expiresAt)
                .used(false)
                .build();

        challengeRepository.save(challenge);
        log.debug("Created challenge with nonce '{}' for device '{}'", nonce, device.getDeviceId());

        return new StartLoginResponse(nonce, challengeValue, challengeProperties.expirationSeconds());
    }

    @Transactional
    public LoginResponse verifyChallenge(VerifyChallengeRequest request) {
        Challenge challenge = challengeRepository.findByNonce(request.nonce())
                .orElseThrow(() -> new IllegalArgumentException("Invalid nonce"));

        if (challenge.isUsed()) {
            throw new IllegalArgumentException("Challenge already used");
        }

        if (challenge.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Challenge expired");
        }

        Device device = deviceRepository.findByDeviceId(challenge.getDeviceId())
                .orElseThrow(() -> new IllegalStateException(
                        "Device not found for challenge: " + challenge.getDeviceId()));

        boolean signatureValid = verifySignature(
                device.getPublicKey(),
                challenge.getChallengeValue(),
                request.signature()
        );

        if (!signatureValid) {
            throw new SecurityException("Invalid signature");
        }

        challenge.setUsed(true);
        challengeRepository.save(challenge);

        // The JWT sub must match the federated identity userId registered in Keycloak
        // (createUserWithFederatedIdentity uses userId, not deviceId, as the external subject).
        String userId = device.getUserId();
        log.info("About to issue Keycloak assertion token for device '{}', userId='{}', keycloakUserId='{}'",
                device.getDeviceId(), userId, device.getKeycloakUserId());
        String assertionToken = jwtService.issueKeycloakAssertionToken(userId, ACR_BIOMETRIC);
        log.info("Issued Keycloak assertion token for device '{}' (userId '{}')",
                device.getDeviceId(), userId);

        KeycloakTokenResponse kcTokens = keycloakAuthClient.authenticate(assertionToken);
        log.info("Authentication successful for device '{}'", device.getDeviceId());

        String requiredAction = null;
        if (device.getKeycloakUserId() != null && !keycloakAdminClient.hasPassword(device.getKeycloakUserId())) {
            requiredAction = "SET_PASSWORD";
            log.info("User '{}' has no password, requiring SET_PASSWORD action", userId);
        }

        return new LoginResponse(
                kcTokens.accessToken(),
                kcTokens.idToken(),
                kcTokens.refreshToken(),
                kcTokens.expiresIn(),
                kcTokens.tokenType(),
                kcTokens.scope(),
                requiredAction
        );
    }

    private boolean verifySignature(String publicKeyPem, String challengeValue, String signatureBase64) {
        try {
            PublicKey publicKey = KeyLoader.parsePublicKey(publicKeyPem);

            byte[] signatureBytes = Base64.getUrlDecoder().decode(signatureBase64);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(challengeValue.getBytes(StandardCharsets.UTF_8));
            return sig.verify(signatureBytes);
        } catch (GeneralSecurityException e) {
            // Crypto errors (bad key format, invalid signature encoding, etc.) are
            // expected for invalid input — treat as a failed verification.
            log.warn("Signature verification failed (crypto error): {}", e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            // Base64 decoding failure — malformed input from the client.
            log.warn("Signature verification failed (encoding error): {}", e.getMessage());
            return false;
        }
        // Any other unexpected exception (e.g. NullPointerException, programming bug)
        // is intentionally NOT caught here so it propagates and surfaces as a 500 error.
    }

    /**
     * Removes expired challenges from the database every 10 minutes.
     * This prevents unbounded growth of the challenges table.
     */
    @Scheduled(fixedRateString = "PT10M")
    @Transactional
    public void purgeExpiredChallenges() {
        int deleted = challengeRepository.deleteExpiredChallenges(OffsetDateTime.now());
        if (deleted > 0) {
            log.info("Purged {} expired challenge(s)", deleted);
        }
    }
}
