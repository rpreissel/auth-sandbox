package dev.authsandbox.authservice.dto;

import jakarta.validation.constraints.NotBlank;

public record StartLoginRequest(
        @NotBlank(message = "publicKeyHash must not be blank")
        String publicKeyHash
) {}
