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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private DeviceRepository deviceRepository;
    @Mock private ChallengeRepository challengeRepository;
    @Mock private JwtService jwtService;
    @Mock private KeycloakAuthClient keycloakAuthClient;
    @Mock private KeycloakAdminClient keycloakAdminClient;

    private AuthService authService;
    private ChallengeProperties challengeProperties;

    /** RSA key pair generated once per test class to avoid expensive generation per test. */
    private static KeyPair RSA_KEY_PAIR;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            RSA_KEY_PAIR = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        challengeProperties = new ChallengeProperties(120L);
        authService = new AuthService(deviceRepository, challengeRepository, jwtService, keycloakAuthClient, keycloakAdminClient, challengeProperties);
    }

    // -----------------------------------------------------------------------
    // startLogin
    // -----------------------------------------------------------------------

    @Test
    void startLogin_returnsChallenge_forKnownDevice() {
        Device device = Device.builder()
                .deviceId("dev-001")
                .userId("user-001")
                .name("Test Device")
                .publicKey(pemPublicKey())
                .build();
        when(deviceRepository.findByDeviceId("dev-001")).thenReturn(Optional.of(device));
        when(challengeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StartLoginResponse response = authService.startLogin(new StartLoginRequest("dev-001"));

        assertThat(response.nonce()).isNotBlank();
        assertThat(response.challenge()).isNotBlank();
        assertThat(response.expiresInSeconds()).isEqualTo(120L);
    }

    @Test
    void startLogin_throwsIllegalArgument_forUnknownDevice() {
        when(deviceRepository.findByDeviceId("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.startLogin(new StartLoginRequest("unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown device");
    }

    @Test
    void startLogin_persistsChallenge_withCorrectDeviceId() {
        Device device = Device.builder()
                .deviceId("dev-002")
                .userId("user-002")
                .name("Test Device 2")
                .publicKey(pemPublicKey())
                .build();
        when(deviceRepository.findByDeviceId("dev-002")).thenReturn(Optional.of(device));
        when(challengeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        authService.startLogin(new StartLoginRequest("dev-002"));

        ArgumentCaptor<Challenge> captor = ArgumentCaptor.forClass(Challenge.class);
        verify(challengeRepository).save(captor.capture());
        assertThat(captor.getValue().getDeviceId()).isEqualTo("dev-002");
        assertThat(captor.getValue().getExpiresAt()).isAfter(OffsetDateTime.now());
    }

    // -----------------------------------------------------------------------
    // verifyChallenge
    // -----------------------------------------------------------------------

    @Test
    void verifyChallenge_throwsIllegalArgument_forUnknownNonce() {
        when(challengeRepository.findByNonce("bad-nonce")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyChallenge(new VerifyChallengeRequest("bad-nonce", "sig")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid nonce");
    }

    @Test
    void verifyChallenge_throwsIllegalArgument_forAlreadyUsedChallenge() {
        Challenge usedChallenge = Challenge.builder()
                .deviceId("dev-003")
                .challengeValue("deadbeef")
                .nonce("nonce-001")
                .expiresAt(OffsetDateTime.now().plusSeconds(60))
                .used(true)
                .build();
        when(challengeRepository.findByNonce("nonce-001")).thenReturn(Optional.of(usedChallenge));

        assertThatThrownBy(() -> authService.verifyChallenge(new VerifyChallengeRequest("nonce-001", "sig")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Challenge already used");
    }

    @Test
    void verifyChallenge_throwsIllegalArgument_forExpiredChallenge() {
        Challenge expired = Challenge.builder()
                .deviceId("dev-004")
                .challengeValue("aabbccdd")
                .nonce("nonce-002")
                .expiresAt(OffsetDateTime.now().minusSeconds(1))
                .used(false)
                .build();
        when(challengeRepository.findByNonce("nonce-002")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.verifyChallenge(new VerifyChallengeRequest("nonce-002", "sig")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Challenge expired");
    }

    @Test
    void verifyChallenge_throwsSecurityException_forInvalidSignature() {
        String challengeValue = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
        Challenge challenge = Challenge.builder()
                .deviceId("dev-005")
                .challengeValue(challengeValue)
                .nonce("nonce-003")
                .expiresAt(OffsetDateTime.now().plusSeconds(60))
                .used(false)
                .build();
        Device device = Device.builder()
                .deviceId("dev-005")
                .userId("user-005")
                .name("Test Device 5")
                .publicKey(pemPublicKey())
                .build();
        when(challengeRepository.findByNonce("nonce-003")).thenReturn(Optional.of(challenge));
        when(deviceRepository.findByDeviceId("dev-005")).thenReturn(Optional.of(device));

        // A random base64url value — definitely not a valid signature over this challenge
        String badSignature = Base64.getUrlEncoder().encodeToString("not-a-real-sig".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> authService.verifyChallenge(new VerifyChallengeRequest("nonce-003", badSignature)))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Invalid signature");
    }

    @Test
    void purgeExpiredChallenges_callsRepository() {
        when(challengeRepository.deleteExpiredChallenges(any())).thenReturn(3);

        authService.purgeExpiredChallenges();

        verify(challengeRepository).deleteExpiredChallenges(any(OffsetDateTime.class));
    }

    // -----------------------------------------------------------------------
    // verifyChallenge - requiredAction tests
    // -----------------------------------------------------------------------

    @Test
    void verifyChallenge_returnsNullRequiredAction_whenUserHasPassword() throws Exception {
        String challengeValue = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
        Challenge challenge = Challenge.builder()
                .deviceId("dev-006")
                .challengeValue(challengeValue)
                .nonce("nonce-004")
                .expiresAt(OffsetDateTime.now().plusSeconds(60))
                .used(false)
                .build();
        Device device = Device.builder()
                .deviceId("dev-006")
                .userId("user-006")
                .name("Test Device 6")
                .publicKey(pemPublicKey())
                .keycloakUserId("kc-user-006")
                .build();

        when(challengeRepository.findByNonce("nonce-004")).thenReturn(Optional.of(challenge));
        when(deviceRepository.findByDeviceId("dev-006")).thenReturn(Optional.of(device));
        lenient().when(keycloakAdminClient.hasPassword("kc-user-006")).thenReturn(true);
        when(keycloakAuthClient.authenticate(any())).thenReturn(
                new KeycloakTokenResponse("access", "id", "refresh", 300, "Bearer", "openid"));

        LoginResponse response = authService.verifyChallenge(
                new VerifyChallengeRequest("nonce-004", signChallenge(challengeValue)));

        assertThat(response.requiredAction()).isNull();
    }

    @Test
    void verifyChallenge_returnsSetPasswordRequiredAction_whenUserHasNoPassword() throws Exception {
        String challengeValue = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
        Challenge challenge = Challenge.builder()
                .deviceId("dev-007")
                .challengeValue(challengeValue)
                .nonce("nonce-005")
                .expiresAt(OffsetDateTime.now().plusSeconds(60))
                .used(false)
                .build();
        Device device = Device.builder()
                .deviceId("dev-007")
                .userId("user-007")
                .name("Test Device 7")
                .publicKey(pemPublicKey())
                .keycloakUserId("kc-user-007")
                .build();

        when(challengeRepository.findByNonce("nonce-005")).thenReturn(Optional.of(challenge));
        when(deviceRepository.findByDeviceId("dev-007")).thenReturn(Optional.of(device));
        when(keycloakAdminClient.hasPassword("kc-user-007")).thenReturn(false);
        when(keycloakAuthClient.authenticate(any())).thenReturn(
                new KeycloakTokenResponse("access", "id", "refresh", 300, "Bearer", "openid"));

        LoginResponse response = authService.verifyChallenge(
                new VerifyChallengeRequest("nonce-005", signChallenge(challengeValue)));

        assertThat(response.requiredAction()).isEqualTo("SET_PASSWORD");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String pemPublicKey() {
        byte[] encoded = RSA_KEY_PAIR.getPublic().getEncoded();
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----";
    }

    /** Signs the given challenge hex string with the test RSA private key, returns base64url. */
    @SuppressWarnings("unused")
    static String signChallenge(String challengeHex) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(RSA_KEY_PAIR.getPrivate());
        sig.update(challengeHex.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
    }
}
