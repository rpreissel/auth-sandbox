package dev.authsandbox.authservice.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyChallengeRequest(
        @NotBlank(message = "nonce must not be blank")
        String nonce,

        @NotBlank(message = "encryptedKey must not be blank")
        String encryptedKey,

        @NotBlank(message = "encryptedData must not be blank")
        String encryptedData,

        @NotBlank(message = "iv must not be blank")
        String iv,

        @NotBlank(message = "signature must not be blank")
        String signature
) {}
