package dev.authsandbox.devicelogin.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterDeviceRequest(
        @NotBlank(message = "deviceId must not be blank")
        String deviceId,

        @NotBlank(message = "publicKey must not be blank")
        String publicKey
) {}
