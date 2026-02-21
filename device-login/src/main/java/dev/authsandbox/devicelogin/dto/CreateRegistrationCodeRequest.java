package dev.authsandbox.devicelogin.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateRegistrationCodeRequest(
        @NotBlank(message = "userId must not be blank")
        String userId,

        @NotBlank(message = "name must not be blank")
        String name,

        /**
         * Plain-text activation password supplied by the admin.
         * Will be BCrypt-hashed before storage — never persisted in plain text.
         */
        @NotBlank(message = "activationCode must not be blank")
        String activationCode
) {}
