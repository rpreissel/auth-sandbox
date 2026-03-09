package dev.authsandbox.authservice.dto;

public record StartLoginResponse(
        String nonce,
        String encryptedKey,
        String encryptedData,
        String iv
) {}
