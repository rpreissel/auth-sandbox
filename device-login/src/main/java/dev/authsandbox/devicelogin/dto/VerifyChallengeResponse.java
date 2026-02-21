package dev.authsandbox.devicelogin.dto;

public record VerifyChallengeResponse(
        String deviceToken,
        long expiresInSeconds,
        String tokenType
) {}
