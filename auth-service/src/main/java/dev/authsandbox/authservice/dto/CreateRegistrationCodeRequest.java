package dev.authsandbox.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

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
        String activationCode,

        /**
         * How many hours the code should remain valid.
         * Must be a positive integer. Defaults to 24 if omitted (null).
         */
        @Positive(message = "validForHours must be a positive number")
        Integer validForHours
) {}
