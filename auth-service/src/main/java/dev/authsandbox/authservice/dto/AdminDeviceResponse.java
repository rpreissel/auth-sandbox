package dev.authsandbox.authservice.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminDeviceResponse(
        UUID id,
        String deviceId,
        String userId,
        String name,
        String keycloakUserId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
