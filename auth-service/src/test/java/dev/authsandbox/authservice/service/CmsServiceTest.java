package dev.authsandbox.authservice.service;

import dev.authsandbox.authservice.config.KeycloakCmsProperties;
import dev.authsandbox.authservice.dto.CmsPageRequest;
import dev.authsandbox.authservice.dto.CmsPageResponse;
import dev.authsandbox.authservice.entity.CmsPage;
import dev.authsandbox.authservice.repository.CmsPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CmsServiceTest {

    @Mock
    private CmsPageRepository cmsPageRepository;

    private KeycloakCmsProperties cmsProperties;
    private CmsService cmsService;

    @BeforeEach
    void setUp() {
        cmsProperties = new KeycloakCmsProperties(
                "cms-client",
                "cms-secret",
                "https://cms.localhost:8443/cms/callback",
                "http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token/introspect",
                "https://keycloak.localhost:8443/realms/auth-sandbox/protocol/openid-connect/auth",
                "http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token"
        );

        cmsService = new CmsService(cmsPageRepository, cmsProperties, RestClient.create());
    }

    @Test
    void resolveAccess_public_returnsContentPath() {
        CmsPage page = CmsPage.builder()
                .id(UUID.randomUUID())
                .name("public")
                .key("pub001")
                .protectionLevel("public")
                .contentPath("/cms-content/index.html")
                .build();

        when(cmsPageRepository.findByKey("pub001")).thenReturn(Optional.of(page));

        CmsService.CmsAccessResult result = cmsService.resolveAccess("pub001", "public", null);

        assertThat(result.url()).isEqualTo("/cms-content/index.html");
        assertThat(result.isContentUrl()).isTrue();
    }

    @Test
    void resolveAccess_acr1_noToken_returnsKeycloakUrl() {
        CmsPage page = CmsPage.builder()
                .id(UUID.randomUUID())
                .name("premium")
                .key("prm001")
                .protectionLevel("acr1")
                .contentPath("/cms-content/premium.html")
                .build();

        when(cmsPageRepository.findByKey("prm001")).thenReturn(Optional.of(page));

        CmsService.CmsAccessResult result = cmsService.resolveAccess("prm001", "premium", null);

        assertThat(result.isContentUrl()).isFalse();
        assertThat(result.url()).startsWith("https://keycloak.localhost");
    }

    @Test
    void resolveAccess_unknownKey_throwsNotFoundException() {
        when(cmsPageRepository.findByKey("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cmsService.resolveAccess("unknown", "unknown", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void resolveAccess_nameMismatch_throwsNotFoundException() {
        CmsPage page = CmsPage.builder()
                .id(UUID.randomUUID())
                .name("premium")
                .key("prm001")
                .protectionLevel("acr1")
                .contentPath("/cms-content/premium.html")
                .build();

        when(cmsPageRepository.findByKey("prm001")).thenReturn(Optional.of(page));

        assertThatThrownBy(() -> cmsService.resolveAccess("prm001", "wrongname", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void buildKeycloakAuthUrl_containsCorrectParams() {
        CmsService.CmsAccessResult result = cmsService.buildKeycloakAuthUrl("2", "/p/adm001-admin");

        assertThat(result.url()).contains("acr_values=2");
        assertThat(result.url()).contains("client_id=cms-client");
        assertThat(result.url()).contains("response_type=code");
        assertThat(result.url()).contains("scope=openid");
        assertThat(result.url()).contains("redirect_uri=");
        assertThat(result.isContentUrl()).isFalse();
    }

    @Test
    void createPage_savesEntity() {
        CmsPageRequest request = new CmsPageRequest("test", "tst001", "public", "/cms-content/test.html");

        CmsPage savedPage = CmsPage.builder()
                .id(UUID.randomUUID())
                .name("test")
                .key("tst001")
                .protectionLevel("public")
                .contentPath("/cms-content/test.html")
                .build();

        when(cmsPageRepository.save(any(CmsPage.class))).thenReturn(savedPage);

        CmsPageResponse response = cmsService.createPage(request);

        assertThat(response.name()).isEqualTo("test");
        assertThat(response.key()).isEqualTo("tst001");
    }

    @Test
    void deletePage_deletesIfExists() {
        UUID id = UUID.randomUUID();
        when(cmsPageRepository.existsById(id)).thenReturn(true);

        cmsService.deletePage(id);
    }

    @Test
    void deletePage_throwsIfNotFound() {
        UUID id = UUID.randomUUID();
        when(cmsPageRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> cmsService.deletePage(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }
}
