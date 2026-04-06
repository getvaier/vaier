package net.vaier.rest;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FaviconFetcherService {

    private static final Pattern LINK_TAG =
            Pattern.compile("<link([^>]+)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern REL_ATTR =
            Pattern.compile("rel=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_ATTR =
            Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE_ATTR =
            Pattern.compile("type=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    final Map<String, Optional<byte[]>> cache = new ConcurrentHashMap<>();
    private final HttpClient htmlClient;
    private final HttpClient bytesClient;

    public FaviconFetcherService() {
        this.htmlClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.bytesClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public Optional<byte[]> fetch(String host) {
        if (cache.containsKey(host)) return cache.get(host);

        String baseUrl = "https://" + host;
        Optional<byte[]> result = Optional.empty();
        try {
            String html = fetchHtml(baseUrl);
            if (html != null) {
                Optional<String> faviconUrl = extractFaviconUrl(html, baseUrl);
                if (faviconUrl.isPresent()) {
                    Optional<byte[]> bytes = fetchBytes(faviconUrl.get());
                    if (bytes.isPresent()) result = bytes;
                }
            }
        } catch (Exception ignored) {
        }
        if (result.isEmpty()) result = fetchBytes(baseUrl + "/favicon.ico");
        if (result.isEmpty()) result = fetchBytes(baseUrl + "/apple-touch-icon.png");
        if (result.isEmpty()) result = fetchBytes(baseUrl + "/apple-touch-icon-precomposed.png");
        if (result.isEmpty()) {
            String serviceName = host.split("\\.")[0].toLowerCase();
            for (String iconUrl : internetIconUrls(serviceName)) {
                result = fetchBytes(iconUrl);
                if (result.isPresent()) break;
            }
        }
        cache.put(host, result);
        return result;
    }

    Optional<String> extractFaviconUrl(String html, String baseUrl) {
        List<String> candidates = new ArrayList<>();

        Matcher linkMatcher = LINK_TAG.matcher(html);
        while (linkMatcher.find()) {
            String attrs = linkMatcher.group(1);
            Matcher relMatcher = REL_ATTR.matcher(attrs);
            if (!relMatcher.find()) continue;

            String rel = relMatcher.group(1).toLowerCase();
            if (!rel.contains("icon")) continue;

            Matcher hrefMatcher = HREF_ATTR.matcher(attrs);
            if (!hrefMatcher.find()) continue;

            String href = hrefMatcher.group(1).trim();
            if (href.isEmpty() || href.startsWith("data:")) continue;

            String resolved = resolveUrl(href, baseUrl);

            Matcher typeMatcher = TYPE_ATTR.matcher(attrs);
            if (typeMatcher.find()) {
                String type = typeMatcher.group(1).toLowerCase();
                if (type.contains("png") || type.contains("svg") || type.contains("webp")) {
                    candidates.add(0, resolved); // prefer non-ico
                    continue;
                }
            }
            candidates.add(resolved);
        }

        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    private String resolveUrl(String href, String baseUrl) {
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        if (href.startsWith("//")) return "https:" + href;
        if (href.startsWith("/")) return URI.create(baseUrl).resolve(href).toString();
        return baseUrl + "/" + href;
    }

    private String fetchHtml(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "text/html")
                    .header("User-Agent", "Vaier/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = htmlClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return response.body();
        } catch (Exception ignored) {
        }
        return null;
    }

    private Optional<byte[]> fetchBytes(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Vaier/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = bytesClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (response.statusCode() == 200 && body.length > 0 && looksLikeImage(response.headers(), body)) {
                return Optional.of(body);
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    List<String> internetIconUrls(String serviceName) {
        String name = serviceName.toLowerCase();
        return List.of(
            "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@main/png/" + name + ".png",
            "https://cdn.simpleicons.org/" + name
        );
    }

    private boolean looksLikeImage(java.net.http.HttpHeaders headers, byte[] body) {
        String contentType = headers.firstValue("content-type").orElse("");
        if (contentType.startsWith("image/")) return true;
        // Detect by magic bytes when content-type is missing or generic
        if (body.length >= 4 && (body[0] & 0xFF) == 0x89 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G') return true; // PNG
        if (body.length >= 3 && body[0] == 'G' && body[1] == 'I' && body[2] == 'F') return true; // GIF
        if (body.length >= 2 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8) return true; // JPEG
        if (body.length >= 4 && body[0] == 0 && body[1] == 0 && body[2] == 1 && body[3] == 0) return true; // ICO
        if (body.length > 4 && body[0] == '<') return true; // SVG
        return false;
    }
}
