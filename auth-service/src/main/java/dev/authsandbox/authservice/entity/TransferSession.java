package dev.authsandbox.authservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Server-side storage for the PKCE {@code code_verifier} used in the SSO transfer flow.
 *
 * <p>The {@code code_verifier} is never embedded in the Transfer-JWT or placed in a URL.
 * It is stored here and looked up by {@code sessionId} during the {@code /redeem} step,
 * then marked as {@code redeemed} so it cannot be used a second time.
 */
@Entity
@Table(
        name = "transfer_sessions",
        schema = "device_login"
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Opaque identifier embedded in the Transfer-JWT (not a secret). */
    @Column(name = "session_id", nullable = false, unique = true)
    private String sessionId;

    /** PKCE code_verifier — kept server-side, never exposed in a URL. */
    @Column(name = "code_verifier", nullable = false, columnDefinition = "TEXT")
    private String codeVerifier;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** Set to {@code true} once the verifier has been consumed at the /redeem step. */
    @Column(name = "redeemed", nullable = false)
    private boolean redeemed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        redeemed = false;
    }
}
