package dev.authsandbox.devicelogin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminRegistrationCodeResponse(
        UUID id,
        String userId,
        String name,
        boolean used,
        OffsetDateTime createdAt,
        OffsetDateTime usedAt
) {}
