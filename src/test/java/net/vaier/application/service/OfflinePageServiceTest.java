package net.vaier.application.service;

import net.vaier.application.GetOfflinePageUseCase.OfflinePage;
import net.vaier.config.ConfigResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class OfflinePageServiceTest {

    private OfflinePageService service(String domain) {
        ConfigResolver configResolver = Mockito.mock(ConfigResolver.class);
        Mockito.when(configResolver.getDomain()).thenReturn(domain);
        return new OfflinePageService(configResolver);
    }

    @Test
    void rendersBrandedHtmlNamingTheUnreachableServiceHost() {
        OfflinePage page = service("example.com").render(502, "foo.example.com");

        assertThat(page.status()).isEqualTo(502);
        assertThat(page.contentType()).startsWith("text/html");
        assertThat(page.html()).contains("foo.example.com");
        // friendly message from the domain mapping
        assertThat(page.html().toLowerCase()).contains("unavailable");
    }

    @Test
    void includesRetryAndDashboardLinks() {
        OfflinePage page = service("example.com").render(502, "foo.example.com");

        // Dashboard link points at the Vaier launchpad on the configured domain.
        assertThat(page.html()).contains("https://vaier.example.com/");
        // Retry reloads the current URL (no hard-coded host that would break under the failed service).
        assertThat(page.html()).contains("location.reload");
    }

    @Test
    void selfContainedWithInlineStylesAndNoCrossOriginAssetLinks() {
        OfflinePage page = service("example.com").render(503, "bar.example.com");

        assertThat(page.html()).contains("<style");
        // No <link rel=stylesheet> or <script src=...> to vaier.<domain> — those would fail under
        // the offline service's own hostname.
        assertThat(page.html()).doesNotContain("rel=\"stylesheet\"");
        assertThat(page.html()).doesNotContain("src=\"https://vaier.example.com");
    }

    @Test
    void missingHostStillRendersAGenericPage() {
        OfflinePage page = service("example.com").render(502, null);

        assertThat(page.status()).isEqualTo(502);
        assertThat(page.html().toLowerCase()).contains("unavailable");
    }
}
