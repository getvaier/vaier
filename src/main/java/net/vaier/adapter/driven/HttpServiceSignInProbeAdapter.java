package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ServiceProbeAnswer;
import net.vaier.domain.port.ForProbingServiceSignIn;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * One GET of a service's backend, handed over as it came: status, challenge, redirect target, content type and
 * the first {@link #BODY_LIMIT} characters. Redirects are not followed — where one leads is the domain's call.
 */
@Component
@Slf4j
public class HttpServiceSignInProbeAdapter implements ForProbingServiceSignIn {

    static final int BODY_LIMIT = 64 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(TIMEOUT)
        .build();

    @Override
    public Optional<ServiceProbeAnswer> probe(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "text/html,*/*")
                .header("User-Agent", "Vaier/1.0")
                .GET()
                .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream in = response.body()) {
                body = new String(in.readNBytes(BODY_LIMIT * 4), StandardCharsets.UTF_8);
            }
            return Optional.of(new ServiceProbeAnswer(response.statusCode(),
                response.headers().firstValue("WWW-Authenticate").orElse(null),
                response.headers().firstValue("Location").orElse(null),
                response.headers().firstValue("Content-Type").orElse(null),
                body.length() > BODY_LIMIT ? body.substring(0, BODY_LIMIT) : body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Sign-in probe of {} failed: {}", url, e.getMessage());
            return Optional.empty();
        }
    }
}
