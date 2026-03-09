package dev.authsandbox.authservice.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterDeviceRequest(
        @NotBlank(message = "userId must not be blank")
        String userId,

        @NotBlank(message = "deviceName must not be blank")
        String deviceName,

        @NotBlank(message = "activationCode must not be blank")
        String activationCode,

        @NotBlank(message = "publicKey must not be blank")
        String publicKey
) {}
