package dev.authsandbox.authservice.dto;

import jakarta.validation.constraints.NotBlank;

public record CmsPageRequest(
        @NotBlank String name,
        @NotBlank String key,
        @NotBlank String protectionLevel,
        @NotBlank String contentPath
) {}
