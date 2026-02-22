package dev.authsandbox.devicelogin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminRegistrationCodeResponse(
        UUID id,
        String userId,
        String name,
        String activationCode,
        OffsetDateTime expiresAt,
        int useCount,
        OffsetDateTime createdAt
) {}
