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
    private ObjectMapper objectMapper;

    private static KeyPair RSA_KEY_PAIR;
    private static KeyPair ENC_KEY_PAIR;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            RSA_KEY_PAIR = gen.generateKeyPair();
            ENC_KEY_PAIR = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        challengeProperties = new ChallengeProperties(120L);
        objectMapper = new ObjectMapper();
        authService = new AuthService(deviceRepository, challengeRepository, jwtService, keycloakAuthClient, keycloakAdminClient, challengeProperties, objectMapper);
    }

    // -----------------------------------------------------------------------
    // startLogin
    // -----------------------------------------------------------------------

    @Test
    void startLogin_returnsEncryptedResponse_forKnownDevice() {
        Device device = Device.builder()
                .userId("user-001")
                .deviceName("Test Device")
                .publicKeyHash("abc123")
                .publicKey(pemPublicKey())
                .encPubKey(Base64.getEncoder().encodeToString(ENC_KEY_PAIR.getPublic().getEncoded()))
                .build();
        when(deviceRepository.findByPublicKeyHash("abc123")).thenReturn(Optional.of(device));
        when(challengeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StartLoginResponse response = authService.startLogin(new StartLoginRequest("abc123"));

        assertThat(response.nonce()).isNotBlank();
        assertThat(response.encryptedKey()).isNotBlank();
        assertThat(response.encryptedData()).isNotBlank();
        assertThat(response.iv()).isNotBlank();
    }

    @Test
    void startLogin_throwsIllegalArgument_forUnknownDevice() {
        when(deviceRepository.findByPublicKeyHash("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.startLogin(new StartLoginRequest("unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown device");
    }

    @Test
    void startLogin_persistsChallenge_withCorrectUserId() {
        Device device = Device.builder()
                .userId("user-002")
                .deviceName("Test Device 2")
                .publicKeyHash("def456")
                .publicKey(pemPublicKey())
                .encPubKey(Base64.getEncoder().encodeToString(ENC_KEY_PAIR.getPublic().getEncoded()))
                .build();
        when(deviceRepository.findByPublicKeyHash("def456")).thenReturn(Optional.of(device));
        when(challengeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        authService.startLogin(new StartLoginRequest("def456"));

        ArgumentCaptor<Challenge> captor = ArgumentCaptor.forClass(Challenge.class);
        verify(challengeRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("user-002");
        assertThat(captor.getValue().getExpiresAt()).isAfter(OffsetDateTime.now());
    }

    // -----------------------------------------------------------------------
    // verifyChallenge
    // -----------------------------------------------------------------------

    @Test
    void verifyChallenge_throwsIllegalArgument_forUnknownNonce() {
        when(challengeRepository.findByNonce("bad-nonce")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyChallenge(buildValidVerifyRequest("bad-nonce")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid nonce");
    }

    @Test
    void verifyChallenge_throwsIllegalArgument_forAlreadyUsedChallenge() {
        Challenge usedChallenge = Challenge.builder()
                .userId("user-003")
                .nonce("nonce-001")
                .expiresAt(OffsetDateTime.now().plusSeconds(60))
                .used(true)
                .build();
        when(challengeRepository.findByNonce("nonce-001")).thenReturn(Optional.of(usedChallenge));

        assertThatThrownBy(() -> authService.verifyChallenge(buildValidVerifyRequest("nonce-001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Challenge already used");
    }

    @Test
    void verifyChallenge_throwsIllegalArgument_forExpiredChallenge() {
        Challenge expired = Challenge.builder()
                .userId("user-004")
                .nonce("nonce-002")
                .expiresAt(OffsetDateTime.now().minusSeconds(1))
                .used(false)
                .build();
        when(challengeRepository.findByNonce("nonce-002")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.verifyChallenge(buildValidVerifyRequest("nonce-002")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Challenge expired");
    }

    @Test
    void verifyChallenge_delegatesAuthToKeycloak() throws Exception {
        Challenge challenge = Challenge.builder()
                .userId("user-005")
                .nonce("nonce-003")
                .expiresAt(OffsetDateTime.now().plusSeconds(60))
                .used(false)
                .build();
        when(challengeRepository.findByNonce("nonce-003")).thenReturn(Optional.of(challenge));
        when(keycloakAuthClient.authenticate(any())).thenReturn(
                new KeycloakTokenResponse("access", "id", "refresh", 300, "Bearer", "openid"));

        LoginResponse response = authService.verifyChallenge(buildValidVerifyRequest("nonce-003"));

        assertThat(response.accessToken()).isEqualTo("access");
        verify(keycloakAuthClient).authenticate(any());
    }

    @Test
    void purgeExpiredChallenges_callsRepository() {
        when(challengeRepository.deleteExpiredChallenges(any())).thenReturn(3);

        authService.purgeExpiredChallenges();

        verify(challengeRepository).deleteExpiredChallenges(any(OffsetDateTime.class));
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

    private VerifyChallengeRequest buildValidVerifyRequest(String nonce) {
        return new VerifyChallengeRequest(
                nonce,
                "encryptedKey",
                "encryptedData",
                "iv",
                "signature"
        );
    }
}
