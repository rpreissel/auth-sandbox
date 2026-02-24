package dev.authsandbox.authservice.dto;

public record InitRequest(
        String accessToken,
        String targetUrl
) {}
