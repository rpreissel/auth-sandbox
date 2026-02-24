package dev.authsandbox.authservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PushedAuthorizationResponse(
        @JsonProperty("request_uri") String requestUri,
        String codeVerifier,
        String state,
        long expiresIn
) {}
