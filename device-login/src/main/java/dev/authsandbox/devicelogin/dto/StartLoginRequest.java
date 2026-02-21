package dev.authsandbox.devicelogin.dto;

import jakarta.validation.constraints.NotBlank;

public record StartLoginRequest(
        @NotBlank(message = "deviceId must not be blank")
        String deviceId
) {}
