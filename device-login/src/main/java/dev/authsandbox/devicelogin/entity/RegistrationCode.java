package dev.authsandbox.devicelogin.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pre-provisioned registration entry created by an admin.
 * Allows exactly one device to register by supplying a matching activation code.
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

    /** {@code true} after a device has successfully registered with this entry. */
    @Column(name = "used", nullable = false)
    private boolean used;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Timestamp of the moment this code was consumed by a device registration. */
    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        used = false;
    }
}
