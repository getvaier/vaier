package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForProbingServiceVersion;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes a service's version endpoint over HTTP. Short timeouts keep a dead endpoint from
 * stalling the launchpad; every failure mode collapses to {@link Optional#empty()}.
 */
@Component
@Slf4j
public class HttpServiceVersionProbeAdapter implements ForProbingServiceVersion {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(TIMEOUT)
        .build();

    @Override
    public Optional<String> probeVersion(String url, String property) {
        if (url == null || url.isBlank() || property == null || property.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(toUri(url))
                .timeout(TIMEOUT)
                .header("Accept", "text/plain")
                .header("User-Agent", "Vaier/1.0")
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Version probe of {} returned status {}", url, response.statusCode());
                return Optional.empty();
            }
            return extractProperty(response.body(), property);
        } catch (Exception e) {
            log.debug("Version probe of {} failed: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Percent-encode the {@code []} a metrics endpoint commonly carries in its query string
     * (e.g. {@code ?name[]=system_info}) — {@link URI#create} rejects those characters raw.
     */
    private static URI toUri(String url) {
        return URI.create(url.replace("[", "%5B").replace("]", "%5D"));
    }

    /**
     * Pull the value out of a {@code property="value"} pair — the label form used by Prometheus
     * text exposition (and any response that labels values the same way). First non-empty match
     * wins; the property name is matched literally.
     */
    static Optional<String> extractProperty(String body, String property) {
        if (body == null || property == null) return Optional.empty();
        Matcher m = Pattern.compile(Pattern.quote(property) + "=\"([^\"]*)\"").matcher(body);
        if (m.find()) {
            String value = m.group(1).trim();
            if (!value.isEmpty()) return Optional.of(value);
        }
        return Optional.empty();
    }
}
