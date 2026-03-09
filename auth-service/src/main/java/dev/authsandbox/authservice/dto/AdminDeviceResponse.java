package dev.authsandbox.authservice.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminDeviceResponse(
        UUID id,
        String userId,
        String deviceName,
        String publicKeyHash,
        String keycloakUserId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
