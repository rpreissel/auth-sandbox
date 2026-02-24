package dev.authsandbox.authservice.dto;

public record StartLoginResponse(
        String nonce,
        String challenge,
        long expiresInSeconds
) {}
