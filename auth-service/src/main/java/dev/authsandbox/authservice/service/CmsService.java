package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.config.KeycloakCmsProperties;
import dev.authsandbox.authservice.dto.CmsPageRequest;
import dev.authsandbox.authservice.dto.CmsPageResponse;
import dev.authsandbox.authservice.dto.KeycloakTokenResponse;
import dev.authsandbox.authservice.entity.CmsPage;
import dev.authsandbox.authservice.repository.CmsPageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CmsService {

    private final CmsPageRepository cmsPageRepository;
    private final KeycloakCmsProperties cmsProperties;
    private final RestClient restClient;

    public record CmsAccessResult(String url, boolean isContentUrl) {}

    public record CallbackResult(String accessToken, String returnUrl) {}

    public CmsAccessResult resolveAccess(String key, String name, String sessionToken) {
        CmsPage page = cmsPageRepository.findByKey(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Page not found"));

        if (!page.getName().equals(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Page name mismatch");
        }

        String protectionLevel = page.getProtectionLevel();

        if ("public".equals(protectionLevel)) {
            return new CmsAccessResult(page.getContentPath(), true);
        }

        String acrLevel = "acr1".equals(protectionLevel) ? "1" : "2";

        if (sessionToken == null || sessionToken.isBlank()) {
            return buildKeycloakAuthUrl(acrLevel, page.getContentPath());
        }

        Map<String, Object> introspection = introspectToken(sessionToken);
        boolean active = Boolean.TRUE.equals(introspection.get("active"));

        if (!active) {
            return buildKeycloakAuthUrl(acrLevel, page.getContentPath());
        }

        Object acrClaim = introspection.get("acr");
        int acrValue = parseAcrClaim(acrClaim);

        int required = "acr1".equals(protectionLevel) ? 1 : 2;

        if (acrValue >= required) {
            return new CmsAccessResult(page.getContentPath(), true);
        }

        return buildKeycloakAuthUrl(String.valueOf(required), page.getContentPath());
    }

    public CmsAccessResult buildKeycloakAuthUrl(String acrLevel, String returnUrl) {
        String encodedState = Base64.getUrlEncoder().encodeToString(
                returnUrl.getBytes(StandardCharsets.UTF_8));

        URI authUri = UriComponentsBuilder.fromUriString(cmsProperties.authPublicEndpoint())
                .queryParam("client_id", cmsProperties.clientId())
                .queryParam("redirect_uri", cmsProperties.callbackUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid")
                .queryParam("acr_values", acrLevel)
                .queryParam("state", encodedState)
                .encode()
                .build()
                .toUri();

        return new CmsAccessResult(authUri.toString(), false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> introspectToken(String token) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("token", token);
        body.add("client_id", cmsProperties.clientId());
        body.add("client_secret", cmsProperties.clientSecret());

        Map<String, Object> response = restClient.post()
                .uri(cmsProperties.introspectEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new RuntimeException("Empty introspection response from Keycloak");
        }

        return response;
    }

    private int parseAcrClaim(Object acrClaim) {
        if (acrClaim == null) {
            return 0;
        }
        if (acrClaim instanceof Number) {
            return ((Number) acrClaim).intValue();
        }
        try {
            return Integer.parseInt(acrClaim.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public CallbackResult callback(String code, String state) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", cmsProperties.clientId());
        body.add("client_secret", cmsProperties.clientSecret());
        body.add("code", code);
        body.add("redirect_uri", cmsProperties.callbackUri());

        KeycloakTokenResponse tokenResponse = restClient.post()
                .uri(cmsProperties.tokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(KeycloakTokenResponse.class);

        if (tokenResponse == null || tokenResponse.accessToken() == null) {
            throw new RuntimeException("Failed to exchange code for tokens");
        }

        String returnUrl;
        try {
            byte[] decoded = Base64.getDecoder().decode(state);
            returnUrl = new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            returnUrl = "/";
        }

        return new CallbackResult(tokenResponse.accessToken(), returnUrl);
    }

    public CmsPageResponse createPage(CmsPageRequest request) {
        CmsPage page = CmsPage.builder()
                .name(request.name())
                .key(request.key())
                .protectionLevel(request.protectionLevel())
                .contentPath(request.contentPath())
                .build();

        CmsPage saved = cmsPageRepository.save(page);
        return toResponse(saved);
    }

    public List<CmsPageResponse> listPages() {
        return cmsPageRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public void deletePage(UUID id) {
        if (!cmsPageRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Page not found");
        }
        cmsPageRepository.deleteById(id);
    }

    private CmsPageResponse toResponse(CmsPage page) {
        return new CmsPageResponse(
                page.getId(),
                page.getName(),
                page.getKey(),
                page.getProtectionLevel(),
                page.getContentPath(),
                page.getCreatedAt()
        );
    }
}
