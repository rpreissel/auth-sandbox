package dev.authsandbox.devicelogin.service;

import dev.authsandbox.devicelogin.config.ChallengeProperties;
import dev.authsandbox.devicelogin.config.JwtProperties;
import dev.authsandbox.devicelogin.dto.StartLoginRequest;
import dev.authsandbox.devicelogin.dto.StartLoginResponse;
import dev.authsandbox.devicelogin.dto.VerifyChallengeRequest;
import dev.authsandbox.devicelogin.dto.VerifyChallengeResponse;
import dev.authsandbox.devicelogin.entity.Challenge;
import dev.authsandbox.devicelogin.entity.Device;
import dev.authsandbox.devicelogin.repository.ChallengeRepository;
import dev.authsandbox.devicelogin.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
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
    private final ChallengeProperties challengeProperties;
    private final JwtProperties jwtProperties;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Transactional
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
    public VerifyChallengeResponse verifyChallenge(VerifyChallengeRequest request) {
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

        String token = jwtService.issueDeviceToken(device.getDeviceId());
        log.info("Issued device token for device '{}'", device.getDeviceId());

        return new VerifyChallengeResponse(token, jwtProperties.expirationSeconds(), "Bearer");
    }

    private boolean verifySignature(String publicKeyPem, String challengeValue, String signatureBase64) {
        try {
            String stripped = publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(stripped);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey publicKey = kf.generatePublic(spec);

            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(challengeValue.getBytes());
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            log.warn("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }
}
