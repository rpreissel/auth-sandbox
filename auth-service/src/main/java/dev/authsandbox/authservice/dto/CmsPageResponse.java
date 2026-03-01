package dev.authsandbox.authservice.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CmsPageResponse(
        UUID id,
        String name,
        String key,
        String protectionLevel,
        String contentPath,
        OffsetDateTime createdAt
) {}
