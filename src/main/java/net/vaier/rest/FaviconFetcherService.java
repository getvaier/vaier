package net.vaier.rest;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private final HttpClient httpClient;

    public FaviconFetcherService() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public Optional<byte[]> fetch(String host) {
        String baseUrl = "https://" + host;
        try {
            String html = fetchHtml(baseUrl);
            if (html != null) {
                Optional<String> faviconUrl = extractFaviconUrl(html, baseUrl);
                if (faviconUrl.isPresent()) {
                    Optional<byte[]> bytes = fetchBytes(faviconUrl.get());
                    if (bytes.isPresent()) return bytes;
                }
            }
        } catch (Exception ignored) {
        }
        return fetchBytes(baseUrl + "/favicon.ico");
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
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (response.statusCode() == 200 && response.body().length > 0
                    && contentType.startsWith("image/")) {
                return Optional.of(response.body());
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }
}
