package dev.authsandbox.authservice.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterDeviceRequest(
        @NotBlank(message = "deviceId must not be blank")
        String deviceId,

        /**
         * Pre-provisioned user identifier supplied by the admin.
         * Must match an unused entry in the registration_codes table.
         */
        @NotBlank(message = "userId must not be blank")
        String userId,

        /**
         * Human-readable display name supplied by the registering user.
         * Must match the name stored for the given userId in registration_codes.
         */
        @NotBlank(message = "name must not be blank")
        String name,

        /**
         * Static activation password that unlocks the registration entry.
         * Verified against the BCrypt hash stored in registration_codes.
         */
        @NotBlank(message = "activationCode must not be blank")
        String activationCode,

        @NotBlank(message = "publicKey must not be blank")
        String publicKey
) {}
