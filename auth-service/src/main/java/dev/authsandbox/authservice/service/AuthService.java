package dev.authsandbox.authservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

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
    private final ObjectMapper objectMapper;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Transactional
    @SuppressWarnings("null")
    public StartLoginResponse startLogin(StartLoginRequest request) {
        Device device = deviceRepository.findByPublicKeyHash(request.publicKeyHash())
                .orElseThrow(() -> new IllegalArgumentException("Unknown device"));

        String encPubKeyB64 = device.getEncPubKey();
        byte[] encPubKeyBytes = Base64.getDecoder().decode(encPubKeyB64);
        PublicKey encPubKey;
        try {
            encPubKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encPubKeyBytes));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse encryption public key", e);
        }

        byte[] nonceBytes = new byte[16];
        SECURE_RANDOM.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);

        long exp = Instant.now().plusSeconds(challengeProperties.expirationSeconds()).getEpochSecond();
        String payloadJson = String.format(
                "{\"userId\":\"%s\",\"nonce\":\"%s\",\"exp\":%d}",
                device.getUserId(), nonce, exp);

        byte[] aesKeyBytes = new byte[32];
        SECURE_RANDOM.nextBytes(aesKeyBytes);
        byte[] iv = new byte[12];
        SECURE_RANDOM.nextBytes(iv);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        try {
            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
            byte[] encryptedData = aesCipher.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));

            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsaCipher.init(Cipher.ENCRYPT_MODE, encPubKey);
            byte[] encryptedKey = rsaCipher.doFinal(aesKeyBytes);

            Challenge challenge = Challenge.builder()
                    .nonce(nonce)
                    .userId(device.getUserId())
                    .expiresAt(OffsetDateTime.ofInstant(Instant.ofEpochSecond(exp), ZoneOffset.UTC))
                    .used(false)
                    .build();

            challengeRepository.save(challenge);
            log.debug("Created challenge with nonce '{}' for device '{}'", nonce, device.getDeviceName());

            return new StartLoginResponse(
                    nonce,
                    Base64.getEncoder().encodeToString(encryptedKey),
                    Base64.getEncoder().encodeToString(encryptedData),
                    Base64.getEncoder().encodeToString(iv)
            );
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
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

        challenge.setUsed(true);
        challengeRepository.save(challenge);

        String userId = challenge.getUserId();

        String tokenJson;
        try {
            tokenJson = objectMapper.writeValueAsString(Map.of(
                    "type", "device",
                    "sub", userId,
                    "encryptedKey", request.encryptedKey(),
                    "encryptedData", request.encryptedData(),
                    "iv", request.iv(),
                    "signature", request.signature()
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create login token", e);
        }

        String loginToken = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tokenJson.getBytes(StandardCharsets.UTF_8));

        log.info("Created login_token for userId '{}'", userId);

        KeycloakTokenResponse kcTokens = keycloakAuthClient.authenticate(loginToken);
        log.info("Authentication successful for userId '{}'", userId);

        String requiredAction = null;

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

    @Scheduled(fixedRateString = "PT10M")
    @Transactional
    public void purgeExpiredChallenges() {
        int deleted = challengeRepository.deleteExpiredChallenges(OffsetDateTime.now());
        if (deleted > 0) {
            log.info("Purged {} expired challenge(s)", deleted);
        }
    }
}
