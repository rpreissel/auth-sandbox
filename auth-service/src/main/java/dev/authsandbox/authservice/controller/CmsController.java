package dev.authsandbox.authservice.controller;

import dev.authsandbox.authservice.dto.CmsPageRequest;
import dev.authsandbox.authservice.dto.CmsPageResponse;
import dev.authsandbox.authservice.service.CmsService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class CmsController {

    private final CmsService cmsService;

    private static final String CMS_SESSION_COOKIE = "cms_session";

    @GetMapping("/p/{key}-{name}")
    public ResponseEntity<Void> resolveAccess(
            @PathVariable String key,
            @PathVariable String name,
            HttpServletRequest request) {

        String sessionToken = extractSessionToken(request);

        CmsService.CmsAccessResult result = cmsService.resolveAccess(key, name, sessionToken);

        if (result.isContentUrl()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(java.net.URI.create(result.url()))
                    .build();
        } else {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(java.net.URI.create(result.url()))
                    .build();
        }
    }

    @GetMapping("/cms/callback")
    public void callback(
            @RequestParam String code,
            @RequestParam String state,
            HttpServletRequest request,
            HttpServletResponse response) {

        CmsService.CallbackResult result = cmsService.callback(code, state);

        Cookie cookie = new Cookie(CMS_SESSION_COOKIE, result.accessToken());
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(3600);

        response.addCookie(cookie);

        String redirectUrl = result.returnUrl();
        if (!redirectUrl.startsWith("http://") && !redirectUrl.startsWith("https://")) {
            redirectUrl = "https://cms.localhost:8443" + redirectUrl;
        }

        try {
            response.sendRedirect(redirectUrl);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to redirect", e);
        }
    }

    @PostMapping("/api/v1/cms/pages")
    public ResponseEntity<CmsPageResponse> createPage(@Valid @RequestBody CmsPageRequest request) {
        CmsPageResponse created = cmsService.createPage(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/v1/cms/pages")
    public List<CmsPageResponse> listPages() {
        return cmsService.listPages();
    }

    @DeleteMapping("/api/v1/cms/pages/{id}")
    public ResponseEntity<Void> deletePage(@PathVariable UUID id) {
        try {
            cmsService.deletePage(id);
            return ResponseEntity.noContent().build();
        } catch (ResponseStatusException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    private String extractSessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (CMS_SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
