package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IconResolutionTest {

    // --- extractIconUrl ---

    @Test
    void extractsIconFromStandardLinkTag() {
        String html = "<html><head><link rel=\"icon\" href=\"/favicon.ico\"></head></html>";
        Optional<String> url = IconResolution.extractIconUrl(html, "https://example.com");
        assertThat(url).contains("https://example.com/favicon.ico");
    }

    @Test
    void extractsIconFromShortcutIconLinkTag() {
        String html = "<html><head><link rel=\"shortcut icon\" href=\"/images/icon.png\"></head></html>";
        Optional<String> url = IconResolution.extractIconUrl(html, "https://example.com");
        assertThat(url).contains("https://example.com/images/icon.png");
    }

    @Test
    void extractsIconWhenHrefComesBeforeRel() {
        String html = "<html><head><link href=\"/favicon.png\" rel=\"icon\" type=\"image/png\"></head></html>";
        Optional<String> url = IconResolution.extractIconUrl(html, "https://sonarr.example.com");
        assertThat(url).contains("https://sonarr.example.com/favicon.png");
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
    void cdnLookupNameUsesFinalPathPrefixSegmentWhenPresent() {
        assertThat(IconResolution.cdnLookupName("services.example.com", "/grafana"))
                .isEqualTo("grafana");
    }

    @Test
    void cdnLookupNameUsesFinalSegmentOfMultiSegmentPathPrefix() {
        assertThat(IconResolution.cdnLookupName("services.example.com", "/team/grafana"))
                .isEqualTo("grafana");
    }

    @Test
    void cdnLookupNameLowercasesPathSegment() {
        assertThat(IconResolution.cdnLookupName("services.example.com", "/Grafana"))
                .isEqualTo("grafana");
    }

    @Test
    void cdnLookupNameFallsBackToFirstDnsLabelWhenPathPrefixIsNull() {
        assertThat(IconResolution.cdnLookupName("pihole.example.com", null))
                .isEqualTo("pihole");
    }

    @Test
    void cdnLookupNameFallsBackToFirstDnsLabelWhenPathPrefixIsEmpty() {
        assertThat(IconResolution.cdnLookupName("pihole.example.com", ""))
                .isEqualTo("pihole");
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
    void contentType_returnsGifForGifMagic() {
        byte[] body = "GIF89a".getBytes();
        assertThat(IconResolution.contentType(body)).isEqualTo("image/gif");
    }

    @Test
    void contentType_returnsJpegForJpegMagic() {
        byte[] body = {(byte) 0xFF, (byte) 0xD8, 0, 0};
        assertThat(IconResolution.contentType(body)).isEqualTo("image/jpeg");
    }

    @Test
    void contentType_returnsSvgForXmlStart() {
        byte[] body = "<svg".getBytes();
        assertThat(IconResolution.contentType(body)).isEqualTo("image/svg+xml");
    }

    @Test
    void contentType_defaultsToIcoForUnknownPayloads() {
        byte[] body = {0, 0, 1, 0};
        assertThat(IconResolution.contentType(body)).isEqualTo("image/x-icon");
    }
}
