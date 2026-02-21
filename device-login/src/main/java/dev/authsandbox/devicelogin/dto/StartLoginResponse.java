package dev.authsandbox.devicelogin.dto;

public record StartLoginResponse(
        String nonce,
        String challenge,
        long expiresInSeconds
) {}
