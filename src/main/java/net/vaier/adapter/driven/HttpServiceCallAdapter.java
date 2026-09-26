package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ServiceCall;
import net.vaier.domain.ServiceCallAnswer;
import net.vaier.domain.port.ForCallingServices;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * A <b>Service call</b> over the JDK's HTTP client, to a published service's backend. Redirects are never
 * followed, so the credential never travels past the address the route points at. Only the status, the
 * content type and at most {@link #MAX_BYTES} of the body come back; no header does.
 */
@Component
@Slf4j
public class HttpServiceCallAdapter implements ForCallingServices {

    static final int MAX_BYTES = 256 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(CONNECT_TIMEOUT)
        .build();

    @Override
    public ServiceCallAnswer call(String url, ServiceCall call, String authorization) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "Vaier/1.0")
                .method(call.method(), call.body() == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(call.body(), StandardCharsets.UTF_8));
            if (call.contentType() != null) {
                request.header("Content-Type", call.contentType());
            }
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            HttpResponse<InputStream> response = httpClient.send(request.build(),
                HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(MAX_BYTES);
                boolean more = in.read() >= 0;
                return new ServiceCallAnswer(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(null), body, more);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unanswered(url, call, e);
        } catch (Exception e) {
            throw unanswered(url, call, e);
        }
    }

    /** Logged by address and method only: the request carried a credential, and its own words may too. */
    private static IllegalStateException unanswered(String url, ServiceCall call, Exception cause) {
        log.warn("Service call {} {} failed: {}", call.method(), url, cause.getClass().getSimpleName());
        return new IllegalStateException("The service did not answer.");
    }
}
