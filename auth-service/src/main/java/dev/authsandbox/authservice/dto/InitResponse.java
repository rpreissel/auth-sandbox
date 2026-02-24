package dev.authsandbox.authservice.dto;

public record InitResponse(
        String transferUrl,
        long expiresInSeconds
) {}
