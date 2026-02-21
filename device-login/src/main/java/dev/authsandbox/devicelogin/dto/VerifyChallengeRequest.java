package dev.authsandbox.devicelogin.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyChallengeRequest(
        @NotBlank(message = "nonce must not be blank")
        String nonce,

        @NotBlank(message = "signature must not be blank")
        String signature
) {}
