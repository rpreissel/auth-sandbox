package dev.authsandbox.devicelogin.dto;

public record ErrorResponse(
        int status,
        String error,
        String message
) {}
