package dev.authsandbox.authservice.dto;

import jakarta.validation.constraints.NotBlank;

public record SetPasswordRequest(
        @NotBlank String password
) {}
