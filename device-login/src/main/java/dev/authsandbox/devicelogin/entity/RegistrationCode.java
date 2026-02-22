package dev.authsandbox.devicelogin.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pre-provisioned registration entry created by an admin.
 * A code is valid until {@code expiresAt} and may be used any number of times
 * within that window — each use registers one device.
 */
@Entity
@Table(name = "registration_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistrationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The human-readable user identifier pre-provisioned by the admin. */
    @Column(name = "user_id", nullable = false, unique = true)
    private String userId;

    /** The human-readable display name pre-provisioned by the admin. */
    @Column(name = "name", nullable = false)
    private String name;

    /**
     * BCrypt hash of the static activation password.
     * Never store or log the plain-text value.
     */
    @Column(name = "activation_code", nullable = false)
    private String activationCode;

    /** Timestamp after which the code is no longer accepted. */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** Number of successful device registrations made with this code. */
    @Column(name = "use_count", nullable = false)
    private int useCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        useCount = 0;
    }

    /** Returns {@code true} if the code has passed its expiry timestamp. */
    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }
}
