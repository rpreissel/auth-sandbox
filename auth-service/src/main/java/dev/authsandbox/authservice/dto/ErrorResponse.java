package dev.authsandbox.authservice.dto;

public record ErrorResponse(
        int status,
        String error,
        String message
) {}
