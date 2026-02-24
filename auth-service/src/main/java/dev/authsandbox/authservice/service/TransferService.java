package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.config.JwtProperties;
import dev.authsandbox.authservice.config.KeycloakProperties;
import dev.authsandbox.authservice.dto.InitRequest;
import dev.authsandbox.authservice.dto.InitResponse;
import dev.authsandbox.authservice.dto.PushedAuthorizationResponse;
import dev.authsandbox.authservice.dto.RedeemResult;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {

    private static final String COOKIE_NAME = "transfer_cv";

    private final JwtService jwtService;
    private final KeycloakTransferClient keycloakTransferClient;
    private final KeycloakAdminClient keycloakAdminClient;
    private final JwtProperties jwtProperties;
    private final KeycloakProperties keycloakProperties;

    /**
     * Initiates a browser SSO transfer.
     *
     * <p>Steps:
     * <ol>
     *   <li>Introspect the caller's Keycloak access token — reject if inactive</li>
     *   <li>Ensure the user has a federated identity link to sso-proxy-idp</li>
     *   <li>Issue a short-lived {@code login_token} JWT for the extracted userId</li>
     *   <li>Issue a signed {@code state} JWT containing the targetUrl</li>
     *   <li>Push the authorization request to Keycloak (PAR)</li>
     *   <li>Encode the PAR {@code request_uri} and PKCE {@code code_verifier}
     *       in a signed Transfer-JWT (60 s TTL)</li>
     *   <li>Return the one-time redeem URL</li>
     * </ol>
     *
     * @param request the init request payload
     * @return a response containing the one-time transfer URL and its TTL
     */
    public InitResponse init(InitRequest request) {
        // 1. Introspect the access token
        Map<String, Object> claims = keycloakTransferClient.introspectToken(request.accessToken());
        Boolean active = (Boolean) claims.get("active");
        if (!Boolean.TRUE.equals(active)) {
            throw new IllegalArgumentException("Access token is not active");
        }

        String userId = (String) claims.get("preferred_username");
        if (userId == null || userId.isBlank()) {
            userId = (String) claims.get("sub");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Cannot determine userId from token claims");
        }
        log.debug("Initiating transfer for userId '{}'", userId);

        // 2. Ensure the user has a sso-proxy-idp federated identity link
        keycloakAdminClient.ensureSsoProxyFederatedIdentityLink(userId);

        // 3. Issue Keycloak assertion token
        String loginToken = jwtService.issueKeycloakAssertionToken(userId);

        // 4. Issue state JWT (carries targetUrl + nonce)
        String stateJwt = jwtService.issueStateToken(request.targetUrl());

        // 5. PAR → Keycloak
        PushedAuthorizationResponse par = keycloakTransferClient.pushAuthorizationRequest(
                loginToken, stateJwt, keycloakProperties.callbackUri());

        // 6. Encode in Transfer-JWT
        String transferToken = jwtService.issueTransferToken(par.requestUri(), par.codeVerifier());

        // 7. Build redeem URL
        String redeemUrl = jwtProperties.issuer() + "/api/v1/transfer/redeem?t=" + transferToken;

        log.info("Transfer initiated for userId '{}', expiresIn={}s", userId,
                jwtProperties.transferTokenTtlSeconds());
        return new InitResponse(redeemUrl, jwtProperties.transferTokenTtlSeconds());
    }

    /**
     * Redeems a Transfer-JWT: verifies the token and returns both the Keycloak PAR
     * authorization URL and the PKCE code_verifier so the controller can plant the
     * code_verifier in an HttpOnly cookie and redirect the browser.
     *
     * @param transferToken the Transfer-JWT from the URL parameter
     * @return a {@link RedeemResult} with the PAR auth URL and code_verifier
     */
    public RedeemResult redeem(String transferToken) {
        Claims claims = jwtService.validateTransferToken(transferToken);

        String requestUri   = claims.get("request_uri", String.class);
        String codeVerifier = claims.get("code_verifier", String.class);

        if (requestUri == null || codeVerifier == null) {
            throw new JwtException("Transfer token is missing required claims");
        }

        log.debug("Transfer token redeemed — building PAR auth URL");
        String authUrl = keycloakTransferClient.buildParAuthUrl(requestUri);
        return new RedeemResult(authUrl, codeVerifier);
    }

    /**
     * Reads the code_verifier from the {@code transfer_cv} cookie that was planted
     * during {@link #redeem(String)}.
     */
    public String readCodeVerifierFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            throw new IllegalArgumentException("No cookies present — transfer_cv cookie missing");
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("transfer_cv cookie not found"));
    }

    /**
     * Handles the OAuth2 callback from Keycloak.
     *
     * <p>Steps:
     * <ol>
     *   <li>Verify and parse the signed state JWT → extract targetUrl</li>
     *   <li>Read the code_verifier from the HttpOnly cookie</li>
     *   <li>Exchange the authorization code for OIDC tokens (tokens discarded —
     *       only the SSO session side-effect matters)</li>
     * </ol>
     *
     * @param code      the authorization code from Keycloak
     * @param stateJwt  the signed state JWT from the callback query parameter
     * @param httpRequest the servlet request (for cookie access)
     * @return the targetUrl to redirect the browser to
     */
    public String callback(String code, String stateJwt, HttpServletRequest httpRequest) {
        // 1. Verify state JWT → extract targetUrl
        Claims stateClaims = jwtService.validateStateToken(stateJwt);
        String targetUrl = stateClaims.get("target_url", String.class);
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new JwtException("State token missing target_url claim");
        }

        // 2. Read code_verifier from cookie
        String codeVerifier = readCodeVerifierFromCookie(httpRequest);

        // 3. Exchange code for tokens (side-effect: Keycloak sets SSO session cookie in browser)
        keycloakTransferClient.exchangeCodeForTokens(code, codeVerifier, keycloakProperties.callbackUri());

        log.info("SSO session established via transfer callback, redirecting to '{}'", targetUrl);
        return targetUrl;
    }

    public static String cookieName() {
        return COOKIE_NAME;
    }
}
