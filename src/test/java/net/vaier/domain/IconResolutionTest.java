package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IconResolutionTest {

    // --- extractIconUrl ---

    @Test
    void extractsIcon_findsTheLinkTagRegardlessOfRelValueOrAttributeOrder() {
        record Row(String description, String html, String base, String expectedUrl) {}
        List<Row> rows = List.of(
            new Row("standard link tag",
                "<html><head><link rel=\"icon\" href=\"/favicon.ico\"></head></html>",
                "https://example.com", "https://example.com/favicon.ico"),
            new Row("shortcut icon link tag",
                "<html><head><link rel=\"shortcut icon\" href=\"/images/icon.png\"></head></html>",
                "https://example.com", "https://example.com/images/icon.png"),
            new Row("href comes before rel",
                "<html><head><link href=\"/favicon.png\" rel=\"icon\" type=\"image/png\"></head></html>",
                "https://sonarr.example.com", "https://sonarr.example.com/favicon.png")
        );

        for (Row row : rows) {
            Optional<String> url = IconResolution.extractIconUrl(row.html(), row.base());
            assertThat(url).as(row.description()).contains(row.expectedUrl());
        }
    }

    @Test
    void returnsAbsoluteHrefAsIs() {
        String html = "<html><head><link rel=\"icon\" href=\"https://cdn.example.com/icon.png\"></head></html>";
        Optional<String> url = IconResolution.extractIconUrl(html, "https://example.com");
        assertThat(url).contains("https://cdn.example.com/icon.png");
    }

    @Test
    void returnsEmptyWhenNoIconLinkPresent() {
        String html = "<html><head><title>My App</title></head></html>";
        Optional<String> url = IconResolution.extractIconUrl(html, "https://example.com");
        assertThat(url).isEmpty();
    }

    @Test
    void prefersSvgOrPngOverIco() {
        String html = "<html><head>" +
                "<link rel=\"icon\" type=\"image/x-icon\" href=\"/favicon.ico\">" +
                "<link rel=\"icon\" type=\"image/png\" href=\"/favicon.png\" sizes=\"32x32\">" +
                "</head></html>";
        Optional<String> url = IconResolution.extractIconUrl(html, "https://example.com");
        assertThat(url).contains("https://example.com/favicon.png");
    }

    @Test
    void appleTouchIconIsAcceptedSinceRelContainsIcon() {
        String html = "<html><head>" +
                "<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">" +
                "<link rel=\"icon\" href=\"/favicon.ico\">" +
                "</head></html>";
        Optional<String> url = IconResolution.extractIconUrl(html, "https://example.com");
        assertThat(url).isPresent();
    }

    // --- cdnLookupName ---

    @Test
    void cdnLookupName_derivesNameFromPathPrefixOrHost() {
        record Row(String description, String host, String pathPrefix, String expected) {}
        List<Row> rows = List.of(
            new Row("uses the final path-prefix segment when present", "services.example.com", "/grafana", "grafana"),
            new Row("uses the final segment of a multi-segment path prefix", "services.example.com", "/team/grafana", "grafana"),
            new Row("lowercases the path segment", "services.example.com", "/Grafana", "grafana"),
            new Row("falls back to the first dns label when path prefix is null", "pihole.example.com", null, "pihole"),
            new Row("falls back to the first dns label when path prefix is empty", "pihole.example.com", "", "pihole")
        );

        for (Row row : rows) {
            assertThat(IconResolution.cdnLookupName(row.host(), row.pathPrefix())).as(row.description()).isEqualTo(row.expected());
        }
    }

    // --- cacheKey ---

    @Test
    void cacheKeyCombinesHostAndPathPrefix() {
        assertThat(IconResolution.cacheKey("services.example.com", "/grafana"))
                .isEqualTo("services.example.com/grafana");
    }

    @Test
    void cacheKeyTreatsNullAndEmptyPathPrefixAsHostOnly() {
        assertThat(IconResolution.cacheKey("solo.example.com", null))
                .isEqualTo("solo.example.com");
        assertThat(IconResolution.cacheKey("solo.example.com", ""))
                .isEqualTo("solo.example.com");
        assertThat(IconResolution.cacheKey("solo.example.com", null))
                .isEqualTo(IconResolution.cacheKey("solo.example.com", ""));
    }

    // --- internetIconUrls ---

    @Test
    void internetIconUrlsIncludesDashboardIconsAndSimpleIcons() {
        List<String> urls = IconResolution.internetIconUrls("pihole");
        assertThat(urls).anyMatch(u -> u.contains("pihole") && u.contains("dashboard-icons"));
        assertThat(urls).anyMatch(u -> u.contains("pihole") && u.contains("simpleicons"));
    }

    @Test
    void internetIconUrlsLowercasesServiceName() {
        List<String> urls = IconResolution.internetIconUrls("OpenHAB");
        assertThat(urls).allMatch(u -> u.contains("openhab"));
    }

    // --- looksLikeImage ---

    @Test
    void looksLikeImage_trueWhenContentTypeStartsWithImage() {
        assertThat(IconResolution.looksLikeImage("image/png", new byte[]{0, 0, 0, 0})).isTrue();
    }

    @Test
    void looksLikeImage_trueForPngMagic() {
        byte[] body = {(byte) 0x89, 'P', 'N', 'G', 0, 0};
        assertThat(IconResolution.looksLikeImage(null, body)).isTrue();
    }

    @Test
    void looksLikeImage_trueForIcoMagic() {
        byte[] body = {0, 0, 1, 0, 0, 0};
        assertThat(IconResolution.looksLikeImage(null, body)).isTrue();
    }

    @Test
    void looksLikeImage_trueForSvgStartingWithAngleBracket() {
        byte[] body = "<svg></svg>".getBytes();
        assertThat(IconResolution.looksLikeImage(null, body)).isTrue();
    }

    @Test
    void looksLikeImage_falseForHtmlPageWithNoImageMagic() {
        byte[] body = "not-an-image".getBytes();
        assertThat(IconResolution.looksLikeImage("text/plain", body)).isFalse();
    }

    // --- contentType ---

    @Test
    void contentType_returnsPngForPngMagic() {
        byte[] body = {(byte) 0x89, 'P', 'N', 'G'};
        assertThat(IconResolution.contentType(body)).isEqualTo("image/png");
    }

    @Test
    void contentType_recognisesGifAndSvgPayloads() {
        record Row(String description, byte[] body, String expected) {}
        List<Row> rows = List.of(
            new Row("gif magic", "GIF89a".getBytes(), "image/gif"),
            new Row("xml start indicates svg", "<svg".getBytes(), "image/svg+xml")
        );

        for (Row row : rows) {
            assertThat(IconResolution.contentType(row.body())).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void contentType_returnsJpegForJpegMagic() {
        byte[] body = {(byte) 0xFF, (byte) 0xD8, 0, 0};
        assertThat(IconResolution.contentType(body)).isEqualTo("image/jpeg");
    }

    @Test
    void contentType_defaultsToIcoForUnknownPayloads() {
        byte[] body = {0, 0, 1, 0};
        assertThat(IconResolution.contentType(body)).isEqualTo("image/x-icon");
    }
}
