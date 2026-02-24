package dev.authsandbox.authservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KeycloakTokenResponse(
        @JsonProperty("access_token")  String accessToken,
        @JsonProperty("id_token")      String idToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in")    long expiresIn,
        @JsonProperty("token_type")    String tokenType,
        @JsonProperty("scope")         String scope
) {}
