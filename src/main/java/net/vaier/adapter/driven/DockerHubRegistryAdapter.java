package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForCheckingRegistryDigests;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class DockerHubRegistryAdapter implements ForCheckingRegistryDigests {

    private static final String AUTH_URL = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:%s:pull";
    private static final String MANIFEST_URL = "https://registry-1.docker.io/v2/%s/manifests/%s";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient httpClient;

    public DockerHubRegistryAdapter() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    DockerHubRegistryAdapter(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Optional<String> getRemoteDigest(String image, String tag) {
        if (isNonDockerHubImage(image)) {
            log.debug("Skipping non-Docker Hub image: {}", image);
            return Optional.empty();
        }

        String normalizedImage = normalizeImage(image);

        try {
            String token = fetchToken(normalizedImage);
            if (token == null) {
                log.warn("Failed to obtain auth token for {}", normalizedImage);
                return Optional.empty();
            }

            return fetchManifestDigest(normalizedImage, tag, token);
        } catch (Exception e) {
            log.warn("Failed to check remote digest for {}:{} - {}", normalizedImage, tag, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isNonDockerHubImage(String image) {
        String stripped = image.contains(":") ? image.substring(0, image.indexOf(':')) : image;
        if (!stripped.contains(".")) return false;
        String firstPart = stripped.split("/")[0];
        return firstPart.contains(".");
    }

    static String normalizeImage(String image) {
        String stripped = image.contains(":") ? image.substring(0, image.indexOf(':')) : image;
        if (!stripped.contains("/")) {
            return "library/" + stripped;
        }
        return stripped;
    }

    private String fetchToken(String image) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(AUTH_URL, image)))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        Matcher matcher = TOKEN_PATTERN.matcher(response.body());
        return matcher.find() ? matcher.group(1) : null;
    }

    private Optional<String> fetchManifestDigest(String image, String tag, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(MANIFEST_URL, image, tag)))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.docker.distribution.manifest.v2+json")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.debug("Manifest request for {}:{} returned status {}", image, tag, response.statusCode());
            return Optional.empty();
        }

        return response.headers()
                .firstValue("docker-content-digest");
    }
}
