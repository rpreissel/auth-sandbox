package dev.authsandbox.authservice.controller;

import dev.authsandbox.authservice.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/transfer")
@RequiredArgsConstructor
public class JwksController {

    private final JwtService jwtService;

    /**
     * GET /api/v1/transfer/.well-known/jwks.json
     *
     * <p>Exposes the RSA public key as a JWK Set so Keycloak's
     * {@code device-login-idp} Identity Provider can verify login_tokens at runtime.
     */
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return jwtService.toJwkSet();
    }
}
