package dev.authsandbox.devicelogin.controller;

import dev.authsandbox.devicelogin.dto.StartLoginRequest;
import dev.authsandbox.devicelogin.dto.StartLoginResponse;
import dev.authsandbox.devicelogin.dto.VerifyChallengeRequest;
import dev.authsandbox.devicelogin.dto.VerifyChallengeResponse;
import dev.authsandbox.devicelogin.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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
     * receives a device JWT token.
     */
    @PostMapping("/login/verify")
    public ResponseEntity<VerifyChallengeResponse> verifyChallenge(
            @Valid @RequestBody VerifyChallengeRequest request) {
        return ResponseEntity.ok(authService.verifyChallenge(request));
    }
}
