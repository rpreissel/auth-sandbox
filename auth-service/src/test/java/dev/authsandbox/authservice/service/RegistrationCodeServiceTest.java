package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.dto.AdminRegistrationCodeResponse;
import dev.authsandbox.authservice.dto.CreateRegistrationCodeRequest;
import dev.authsandbox.authservice.dto.SyncResult;
import dev.authsandbox.authservice.entity.RegistrationCode;
import dev.authsandbox.authservice.repository.RegistrationCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistrationCodeServiceTest {

    @Mock private RegistrationCodeRepository registrationCodeRepository;
    @Mock private KeycloakAdminClient keycloakAdminClient;

    private RegistrationCodeService registrationCodeService;

    @BeforeEach
    void setUp() {
        registrationCodeService = new RegistrationCodeService(registrationCodeRepository, keycloakAdminClient);
    }

    // -----------------------------------------------------------------------
    // createRegistrationCode
    // -----------------------------------------------------------------------

    @Test
    void createRegistrationCode_createsKeycloakUserAndPersistsCode() {
        when(registrationCodeRepository.findByUserId("alice")).thenReturn(Optional.empty());
        when(keycloakAdminClient.getUserIdByUsername("alice")).thenReturn(Optional.empty());
        when(keycloakAdminClient.createUserWithFederatedIdentity("alice", "Alice Smith")).thenReturn("kc-uuid-alice");
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        registrationCodeService.createRegistrationCode(
                new CreateRegistrationCodeRequest("alice", "Alice Smith", "plain-secret", null));

        // Keycloak user must be created at provisioning time.
        verify(keycloakAdminClient).createUserWithFederatedIdentity("alice", "Alice Smith");

        ArgumentCaptor<RegistrationCode> captor = ArgumentCaptor.forClass(RegistrationCode.class);
        verify(registrationCodeRepository).save(captor.capture());

        RegistrationCode saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("alice");
        assertThat(saved.getName()).isEqualTo("Alice Smith");
        // The activation code must be stored as-is, not hashed.
        assertThat(saved.getActivationCode()).isEqualTo("plain-secret");
    }

    @Test
    void createRegistrationCode_defaultValidityIs24Hours() {
        when(registrationCodeRepository.findByUserId("alice")).thenReturn(Optional.empty());
        when(keycloakAdminClient.getUserIdByUsername("alice")).thenReturn(Optional.empty());
        when(keycloakAdminClient.createUserWithFederatedIdentity("alice", "Alice")).thenReturn("kc-uuid-alice");
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OffsetDateTime before = OffsetDateTime.now();
        registrationCodeService.createRegistrationCode(
                new CreateRegistrationCodeRequest("alice", "Alice", "secret", null));
        OffsetDateTime after = OffsetDateTime.now();

        ArgumentCaptor<RegistrationCode> captor = ArgumentCaptor.forClass(RegistrationCode.class);
        verify(registrationCodeRepository).save(captor.capture());

        OffsetDateTime expiresAt = captor.getValue().getExpiresAt();
        assertThat(expiresAt).isAfterOrEqualTo(before.plusHours(RegistrationCodeService.DEFAULT_VALID_FOR_HOURS));
        assertThat(expiresAt).isBeforeOrEqualTo(after.plusHours(RegistrationCodeService.DEFAULT_VALID_FOR_HOURS)
                .plus(1, ChronoUnit.SECONDS));
    }

    @Test
    void createRegistrationCode_usesCustomValidityWindow() {
        when(registrationCodeRepository.findByUserId("bob")).thenReturn(Optional.empty());
        when(keycloakAdminClient.getUserIdByUsername("bob")).thenReturn(Optional.empty());
        when(keycloakAdminClient.createUserWithFederatedIdentity("bob", "Bob")).thenReturn("kc-uuid-bob");
        when(registrationCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OffsetDateTime before = OffsetDateTime.now();
        registrationCodeService.createRegistrationCode(
                new CreateRegistrationCodeRequest("bob", "Bob", "secret", 48));

        ArgumentCaptor<RegistrationCode> captor = ArgumentCaptor.forClass(RegistrationCode.class);
        verify(registrationCodeRepository).save(captor.capture());

        OffsetDateTime expiresAt = captor.getValue().getExpiresAt();
        assertThat(expiresAt).isCloseTo(before.plusHours(48), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void createRegistrationCode_throwsIllegalArgument_forDuplicateUserId() {
        RegistrationCode existing = RegistrationCode.builder()
                .userId("alice")
                .name("Alice")
                .activationCode("$2a$hash")
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .build();
        when(registrationCodeRepository.findByUserId("alice")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->
                registrationCodeService.createRegistrationCode(
                        new CreateRegistrationCodeRequest("alice", "Alice", "secret", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alice");

        // No Keycloak call should be made when the code already exists.
        verifyNoInteractions(keycloakAdminClient);
    }

    // -----------------------------------------------------------------------
    // deleteRegistrationCode
    // -----------------------------------------------------------------------

    @Test
    void deleteRegistrationCode_deletesKeycloakUser_whenNoDeviceRegisteredYet() {
        UUID id = UUID.randomUUID();
        RegistrationCode code = RegistrationCode.builder()
                .userId("bob")
                .name("Bob")
                .activationCode("secret")
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .useCount(0)
                .build();
        when(registrationCodeRepository.findById(id)).thenReturn(Optional.of(code));

        registrationCodeService.deleteRegistrationCode(id);

        verify(keycloakAdminClient).deleteUserByUsername("bob");
        verify(registrationCodeRepository).delete(code);
    }

    @Test
    void deleteRegistrationCode_doesNotDeleteKeycloakUser_whenDeviceAlreadyRegistered() {
        UUID id = UUID.randomUUID();
        RegistrationCode code = RegistrationCode.builder()
                .userId("carol")
                .name("Carol")
                .activationCode("secret")
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .useCount(1)  // device already registered
                .build();
        when(registrationCodeRepository.findById(id)).thenReturn(Optional.of(code));

        registrationCodeService.deleteRegistrationCode(id);

        verifyNoInteractions(keycloakAdminClient);
        verify(registrationCodeRepository).delete(code);
    }

    @Test
    void deleteRegistrationCode_continuesIfKeycloakDeletionFails() {
        UUID id = UUID.randomUUID();
        RegistrationCode code = RegistrationCode.builder()
                .userId("eve")
                .name("Eve")
                .activationCode("secret")
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .useCount(0)
                .build();
        when(registrationCodeRepository.findById(id)).thenReturn(Optional.of(code));
        doThrow(new RuntimeException("Keycloak unavailable")).when(keycloakAdminClient).deleteUserByUsername("eve");

        // Must not throw — Keycloak cleanup failures are logged and ignored.
        registrationCodeService.deleteRegistrationCode(id);

        verify(registrationCodeRepository).delete(code);
    }

    @Test
    void deleteRegistrationCode_throwsNoSuchElement_forUnknownId() {
        UUID id = UUID.randomUUID();
        when(registrationCodeRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registrationCodeService.deleteRegistrationCode(id))
                .isInstanceOf(NoSuchElementException.class);
    }

    // -----------------------------------------------------------------------
    // listRegistrationCodes
    // -----------------------------------------------------------------------

    @Test
    void listRegistrationCodes_returnsAllEntries() {
        OffsetDateTime expires = OffsetDateTime.now().plusHours(24);
        RegistrationCode r1 = RegistrationCode.builder().userId("u1").name("User 1").activationCode("h1")
                .expiresAt(expires).build();
        RegistrationCode r2 = RegistrationCode.builder().userId("u2").name("User 2").activationCode("h2")
                .expiresAt(expires).build();
        when(registrationCodeRepository.findAll()).thenReturn(List.of(r1, r2));

        List<AdminRegistrationCodeResponse> result = registrationCodeService.listRegistrationCodes();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AdminRegistrationCodeResponse::userId)
                .containsExactly("u1", "u2");
    }

    // -----------------------------------------------------------------------
    // syncKeycloakUsers
    // -----------------------------------------------------------------------

    @Test
    void syncKeycloakUsers_createsKeycloakUsersForCodesWithoutId() {
        OffsetDateTime expires = OffsetDateTime.now().plusHours(24);
        RegistrationCode unsynced = RegistrationCode.builder()
                .userId("frank").name("Frank").activationCode("secret")
                .expiresAt(expires).build();
        when(registrationCodeRepository.findAll()).thenReturn(List.of(unsynced));
        when(keycloakAdminClient.getUserIdByUsername("frank")).thenReturn(Optional.empty());
        when(keycloakAdminClient.createUserWithFederatedIdentity("frank", "Frank")).thenReturn("kc-uuid-frank");

        SyncResult result = registrationCodeService.syncKeycloakUsers();

        verify(keycloakAdminClient).createUserWithFederatedIdentity("frank", "Frank");
        assertThat(result.synced()).isEqualTo(1);
        assertThat(result.alreadySynced()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void syncKeycloakUsers_skipsCodesAlreadySynced() {
        OffsetDateTime expires = OffsetDateTime.now().plusHours(24);
        RegistrationCode synced = RegistrationCode.builder()
                .userId("grace").name("Grace").activationCode("secret")
                .expiresAt(expires).build();
        when(registrationCodeRepository.findAll()).thenReturn(List.of(synced));
        when(keycloakAdminClient.getUserIdByUsername("grace")).thenReturn(Optional.of("kc-uuid-grace"));

        SyncResult result = registrationCodeService.syncKeycloakUsers();

        verify(keycloakAdminClient, never()).createUserWithFederatedIdentity(any(), any());
        assertThat(result.synced()).isEqualTo(0);
        assertThat(result.alreadySynced()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void syncKeycloakUsers_countsFailuresWithoutAbortingOtherCodes() {
        OffsetDateTime expires = OffsetDateTime.now().plusHours(24);
        RegistrationCode failing = RegistrationCode.builder()
                .userId("henry").name("Henry").activationCode("secret")
                .expiresAt(expires).build();
        RegistrationCode succeeding = RegistrationCode.builder()
                .userId("iris").name("Iris").activationCode("secret")
                .expiresAt(expires).build();
        when(registrationCodeRepository.findAll()).thenReturn(List.of(failing, succeeding));
        when(keycloakAdminClient.getUserIdByUsername("henry")).thenReturn(Optional.empty());
        when(keycloakAdminClient.getUserIdByUsername("iris")).thenReturn(Optional.empty());
        when(keycloakAdminClient.createUserWithFederatedIdentity("henry", "Henry"))
                .thenThrow(new RuntimeException("Keycloak unavailable"));
        when(keycloakAdminClient.createUserWithFederatedIdentity("iris", "Iris")).thenReturn("kc-uuid-iris");

        SyncResult result = registrationCodeService.syncKeycloakUsers();

        assertThat(result.synced()).isEqualTo(1);
        assertThat(result.alreadySynced()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(1);
    }
}
