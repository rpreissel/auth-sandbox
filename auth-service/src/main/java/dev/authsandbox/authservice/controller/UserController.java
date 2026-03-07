package dev.authsandbox.authservice.controller;

import dev.authsandbox.authservice.config.KeycloakProperties;
import dev.authsandbox.authservice.dto.SetPasswordRequest;
import dev.authsandbox.authservice.service.KeycloakAdminClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakProperties keycloakProperties;

    @GetMapping("/password-status")
    public ResponseEntity<Map<String, Boolean>> getPasswordStatus(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        String userId = getUserIdFromToken(authorization);
        String keycloakUserId = keycloakAdminClient.getUserIdByUsername(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        boolean hasPassword = keycloakAdminClient.hasPassword(keycloakUserId);
        return ResponseEntity.ok(Map.of("hasPassword", hasPassword));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> setPassword(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody SetPasswordRequest request) {
        String userId = getUserIdFromToken(authorization);
        String keycloakUserId = keycloakAdminClient.getUserIdByUsername(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        keycloakAdminClient.setPassword(keycloakUserId, request.password());
        return ResponseEntity.ok().build();
    }

    private String getUserIdFromToken(String authorization) {
        String token = authorization.replace("Bearer ", "");
        String introspectUrl = keycloakProperties.introspectEndpoint();

        @SuppressWarnings("unchecked")
        Map<String, Object> response = org.springframework.web.client.RestClient.create()
                .post()
                .uri(introspectUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("token=" + token + "&client_id=" + keycloakProperties.clientId() 
                        + "&client_secret=" + keycloakProperties.clientSecret())
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                    throw new RuntimeException("Token introspection failed: " + resp.getStatusCode());
                })
                .body(Map.class);

        if (response == null || !Boolean.TRUE.equals(response.get("active"))) {
            throw new SecurityException("Invalid or expired token");
        }

        String userId = (String) response.get("preferred_username");
        if (userId == null || userId.isBlank()) {
            userId = (String) response.get("sub");
        }
        if (userId == null || userId.isBlank()) {
            throw new SecurityException("No subject in token");
        }

        return userId;
    }
}
