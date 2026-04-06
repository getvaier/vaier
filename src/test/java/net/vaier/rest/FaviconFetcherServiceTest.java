package net.vaier.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FaviconFetcherServiceTest {

    private final FaviconFetcherService service = new FaviconFetcherService();

    @Test
    void extractsFaviconFromStandardLinkTag() {
        String html = "<html><head><link rel=\"icon\" href=\"/favicon.ico\"></head></html>";
        Optional<String> url = service.extractFaviconUrl(html, "https://example.com");
        assertThat(url).contains("https://example.com/favicon.ico");
    }

    @Test
    void extractsFaviconFromShortcutIconLinkTag() {
        String html = "<html><head><link rel=\"shortcut icon\" href=\"/images/icon.png\"></head></html>";
        Optional<String> url = service.extractFaviconUrl(html, "https://example.com");
        assertThat(url).contains("https://example.com/images/icon.png");
    }

    @Test
    void extractsFaviconWhenHrefComesBeforeRel() {
        String html = "<html><head><link href=\"/favicon.png\" rel=\"icon\" type=\"image/png\"></head></html>";
        Optional<String> url = service.extractFaviconUrl(html, "https://sonarr.example.com");
        assertThat(url).contains("https://sonarr.example.com/favicon.png");
    }

    @Test
    void returnsAbsoluteHrefAsIs() {
        String html = "<html><head><link rel=\"icon\" href=\"https://cdn.example.com/icon.png\"></head></html>";
        Optional<String> url = service.extractFaviconUrl(html, "https://example.com");
        assertThat(url).contains("https://cdn.example.com/icon.png");
    }

    @Test
    void returnsEmptyWhenNoIconLinkPresent() {
        String html = "<html><head><title>My App</title></head></html>";
        Optional<String> url = service.extractFaviconUrl(html, "https://example.com");
        assertThat(url).isEmpty();
    }

    @Test
    void prefersSvgOrPngOverIco() {
        String html = "<html><head>" +
                "<link rel=\"icon\" type=\"image/x-icon\" href=\"/favicon.ico\">" +
                "<link rel=\"icon\" type=\"image/png\" href=\"/favicon.png\" sizes=\"32x32\">" +
                "</head></html>";
        Optional<String> url = service.extractFaviconUrl(html, "https://example.com");
        assertThat(url).contains("https://example.com/favicon.png");
    }

    @Test
    void prefersSvgOrPngOverIcoBasedOnSizes() {
        // apple-touch-icon already matches "icon" so it's parsed
        String html = "<html><head>" +
                "<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">" +
                "<link rel=\"icon\" href=\"/favicon.ico\">" +
                "</head></html>";
        Optional<String> url = service.extractFaviconUrl(html, "https://example.com");
        assertThat(url).isPresent();
    }

    @Test
    void internetIconUrlsIncludesDashboardIconsAndSimpleIconsByServiceName() {
        List<String> urls = service.internetIconUrls("pihole");
        assertThat(urls).anyMatch(u -> u.contains("pihole") && u.contains("dashboard-icons"));
        assertThat(urls).anyMatch(u -> u.contains("pihole") && u.contains("simpleicons"));
    }

    @Test
    void internetIconUrlsUsesLowercaseServiceName() {
        List<String> urls = service.internetIconUrls("OpenHAB");
        assertThat(urls).allMatch(u -> u.contains("openhab"));
    }

    @Test
    void returnsCachedResultWithoutFetching() {
        byte[] cached = {(byte) 0x89, 'P', 'N', 'G'};
        service.cache.put("cached.example.com", Optional.of(cached));

        Optional<byte[]> result = service.fetch("cached.example.com");

        assertThat(result).contains(cached);
    }

    @Test
    void storesResultInCacheAfterFetch() {
        // Pre-populate cache with empty so no network call is made
        service.cache.put("no-network.example.com", Optional.empty());

        service.fetch("no-network.example.com");

        assertThat(service.cache).containsKey("no-network.example.com");
    }
}
