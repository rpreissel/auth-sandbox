package dev.authsandbox.authservice.controller;

import dev.authsandbox.authservice.dto.InitRequest;
import dev.authsandbox.authservice.dto.InitResponse;
import dev.authsandbox.authservice.dto.RedeemResult;
import dev.authsandbox.authservice.service.JwtService;
import dev.authsandbox.authservice.service.TransferService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transfer")
@RequiredArgsConstructor
@Slf4j
public class TransferController {

    private final TransferService transferService;
    private final JwtService jwtService;

    /**
     * POST /api/v1/transfer/init
     *
     * <p>Called by the mobile app with its current Keycloak access token.
     * Returns a one-time transfer URL the user can open in a browser.
     */
    @PostMapping("/init")
    public ResponseEntity<InitResponse> init(@RequestBody InitRequest request) {
        InitResponse response = transferService.init(request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/transfer/redeem?t={transferToken}
     *
     * <p>Browser opens this URL. The service verifies the Transfer-JWT, plants the
     * {@code transfer_cv} HttpOnly cookie containing the code_verifier, and
     * redirects to Keycloak's PAR authorization URL.
     */
    @GetMapping("/redeem")
    public void redeem(
            @RequestParam("t") String transferToken,
            HttpServletResponse response) throws IOException {

        RedeemResult result = transferService.redeem(transferToken);

        // Plant code_verifier in HttpOnly cookie — read back during callback
        Cookie cvCookie = new Cookie(TransferService.cookieName(), result.codeVerifier());
        cvCookie.setHttpOnly(true);
        cvCookie.setSecure(true);
        cvCookie.setPath("/api/v1/transfer");
        cvCookie.setMaxAge((int) 120); // enough for the Keycloak round-trip
        response.addCookie(cvCookie);

        response.sendRedirect(result.authUrl());
    }

    /**
     * GET /api/v1/transfer/callback?code={code}&state={stateJwt}
     *
     * <p>Keycloak redirects here after the user is authenticated via the login_token.
     * The service verifies the state JWT, reads the code_verifier from the HttpOnly
     * cookie, exchanges the code for tokens (SSO session side-effect only), deletes
     * the cookie, and redirects to the targetUrl from the state JWT.
     */
    @GetMapping("/callback")
    public void callback(
            @RequestParam("code") String code,
            @RequestParam("state") String stateJwt,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        String targetUrl = transferService.callback(code, stateJwt, request);

        // Delete the transfer_cv cookie
        Cookie deleteCookie = new Cookie(TransferService.cookieName(), "");
        deleteCookie.setMaxAge(0);
        deleteCookie.setPath("/api/v1/transfer");
        deleteCookie.setHttpOnly(true);
        deleteCookie.setSecure(true);
        response.addCookie(deleteCookie);

        response.sendRedirect(targetUrl);
    }

    /**
     * GET /api/v1/transfer/.well-known/jwks.json
     *
     * <p>Exposes the RSA public key as a JWK Set so Keycloak's
     * {@code device-login-idp} Identity Provider can verify login_tokens at runtime.
     */
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok(jwtService.toJwkSet());
    }
}
