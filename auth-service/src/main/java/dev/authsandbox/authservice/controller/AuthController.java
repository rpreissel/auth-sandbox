package dev.authsandbox.authservice.controller;

import dev.authsandbox.authservice.dto.KeycloakTokenResponse;
import dev.authsandbox.authservice.dto.LoginResponse;
import dev.authsandbox.authservice.dto.RefreshTokenRequest;
import dev.authsandbox.authservice.dto.StartLoginRequest;
import dev.authsandbox.authservice.dto.StartLoginResponse;
import dev.authsandbox.authservice.dto.VerifyChallengeRequest;
import dev.authsandbox.authservice.service.AuthService;
import dev.authsandbox.authservice.service.JwtService;
import dev.authsandbox.authservice.service.KeycloakAuthClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final KeycloakAuthClient keycloakAuthClient;

    /**
     * Step 1 of the auth flow: mobile app starts login, receives a challenge.
     */
    @PostMapping("/login/start")
    public ResponseEntity<StartLoginResponse> startLogin(
            @Valid @RequestBody StartLoginRequest request) {
        return ResponseEntity.ok(authService.startLogin(request));
    }

    /**
     * Step 2 of the auth flow: mobile app returns signed challenge,
     * receives full OIDC tokens from Keycloak.
     */
    @PostMapping("/login/verify")
    public ResponseEntity<LoginResponse> verifyChallenge(
            @Valid @RequestBody VerifyChallengeRequest request) {
        return ResponseEntity.ok(authService.verifyChallenge(request));
    }

    /**
     * JWKS endpoint — Keycloak's JWT Authorization Grant IdP fetches this
     * to verify login assertion tokens issued by the device-login flow.
     */
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok(jwtService.toJwkSet());
    }

    /**
     * Token refresh: exchanges a Keycloak refresh token for a new set of OIDC tokens.
     * The mobile app calls this endpoint to silently renew expiring access tokens.
     */
    @PostMapping("/token/refresh")
    public ResponseEntity<KeycloakTokenResponse> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(keycloakAuthClient.refreshToken(request.refreshToken()));
    }
}
