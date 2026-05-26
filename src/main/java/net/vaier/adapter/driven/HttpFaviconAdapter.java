package net.vaier.adapter.driven;

import net.vaier.domain.port.ForFetchingFavicons;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@Component
public class HttpFaviconAdapter implements ForFetchingFavicons {

    private final HttpClient htmlClient;
    private final HttpClient bytesClient;

    public HttpFaviconAdapter() {
        // HTML hints often live behind a redirect (root → /login etc) we don't want to follow
        // — we want the page that actually carries the <link rel="icon">. The bytes client does
        // follow redirects because CDNs often 302 to a versioned URL.
        this.htmlClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.bytesClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Override
    public Optional<String> fetchHtml(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "text/html")
                .header("User-Agent", "Vaier/1.0")
                .GET()
                .build();
            HttpResponse<String> response = htmlClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return Optional.of(response.body());
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    @Override
    public Optional<FetchedBytes> fetchBytes(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Vaier/1.0")
                .GET()
                .build();
            HttpResponse<byte[]> response = bytesClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                String contentType = response.headers().firstValue("content-type").orElse(null);
                return Optional.of(new FetchedBytes(response.body(), contentType));
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }
}
