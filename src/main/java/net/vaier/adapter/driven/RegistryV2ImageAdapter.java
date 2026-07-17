package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ImageReference;
import net.vaier.domain.port.ForResolvingRegistryDigest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks any <b>Registry v2</b> host what digest it currently serves for a tag, over the anonymous
 * bearer-token flow. Generic by construction: Docker Hub, {@code ghcr.io} and {@code lscr.io} differ only in
 * the realm they name in their own {@code WWW-Authenticate} challenge, and this adapter reads that challenge
 * rather than knowing anything about any particular registry.
 *
 * <p>Translation only — every judgement about what a digest <em>means</em> is the domain's. This class knows
 * how to have the conversation; {@code UpdateAvailability} decides what the answer implies.
 *
 * <p><b>The flow</b>, one image at a time: {@code HEAD /v2/{repo}/manifests/{tag}} → the registry answers 401
 * with a challenge → fetch a pull-scoped token from the realm it names → repeat the HEAD with the bearer, and
 * read {@code Docker-Content-Digest}. HEAD rather than GET because only the header is wanted, and the manifest
 * body would be paid for on every image. The token asks for {@code :pull} on exactly one repository: Vaier is
 * read-only about containers, and the credential it holds — none — should never be able to do more.
 *
 * <p><b>Every failure is an empty answer.</b> Unreachable, rate-limited (Docker Hub anonymous is roughly 100
 * manifest requests per six hours), 404, still-401 after a token, a body that will not parse: all resolve
 * empty, which the domain reads as unknown rather than outdated. A monitor that cries wolf when the network
 * hiccups is a monitor that gets filtered.
 *
 * <p><b>The cache is not an optimisation.</b> Answers are held for {@value #CACHE_TTL_HOURS} hours, keyed by
 * the fully-qualified image reference, which is what keeps a fleet-wide sweep under the anonymous rate limit.
 * Failures are deliberately <em>not</em> cached: caching "unknown" would let one blip blind Vaier for a day.
 */
@Component
@Slf4j
public class RegistryV2ImageAdapter implements ForResolvingRegistryDigest {

    /** Mirrors {@code DockerServerAdapter}: a healthy registry answers fast; a dead one must not stall a sweep. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** As long as the sweep interval: one answer per image per day is exactly one sweep's worth. */
    private static final int CACHE_TTL_HOURS = 24;

    /**
     * The digest a registry serves depends on what you say you can accept. Both multi-arch index types must be
     * offered (Docker's manifest list and the OCI image index), or a multi-arch registry answers with a
     * single-arch manifest whose digest is not the one the local {@code RepoDigests} records — and every
     * multi-arch image in the fleet would read as permanently out of date. The single-arch types follow for
     * images that genuinely are one architecture.
     */
    private static final String ACCEPT_MANIFEST_TYPES = String.join(", ",
        "application/vnd.docker.distribution.manifest.list.v2+json",
        "application/vnd.oci.image.index.v1+json",
        "application/vnd.docker.distribution.manifest.v2+json",
        "application/vnd.oci.image.manifest.v1+json");

    /** The digest the registry serves, in its own response header. */
    private static final String DIGEST_HEADER = "Docker-Content-Digest";

    /** {@code Bearer realm="https://auth.docker.io/token",service="registry.docker.io"} — realm and service. */
    private static final Pattern REALM = Pattern.compile("realm=\"([^\"]+)\"");
    private static final Pattern SERVICE = Pattern.compile("service=\"([^\"]+)\"");

    private final HttpClient httpClient;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CachedDigest> cache = new ConcurrentHashMap<>();

    private record CachedDigest(String digest, Instant fetchedAt) {}

    public RegistryV2ImageAdapter() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), Clock.systemUTC());
    }

    RegistryV2ImageAdapter(HttpClient httpClient, Clock clock) {
        this.httpClient = httpClient;
        this.clock = clock;
    }

    @Override
    public Optional<String> resolveDigest(ImageReference reference) {
        if (reference == null) {
            return Optional.empty();
        }
        String key = reference.canonical();
        CachedDigest cached = cache.get(key);
        if (cached != null && !isStale(cached)) {
            return Optional.of(cached.digest());
        }
        Optional<String> digest = fetchDigest(reference);
        // Only successes are cached: an outage must not blind the next sweep too.
        digest.ifPresent(d -> cache.put(key, new CachedDigest(d, clock.instant())));
        return digest;
    }

    private boolean isStale(CachedDigest cached) {
        return cached.fetchedAt().plus(Duration.ofHours(CACHE_TTL_HOURS)).isBefore(clock.instant());
    }

    /** The whole conversation, total: any failure at any step is an empty answer, never a throw. */
    private Optional<String> fetchDigest(ImageReference reference) {
        try {
            URI manifestUri = URI.create("https://" + reference.registry()
                + "/v2/" + reference.repository() + "/manifests/" + reference.tag());

            HttpResponse<String> response = headManifest(manifestUri, null);
            if (response.statusCode() == 401) {
                Optional<String> token = fetchAnonymousToken(response, reference);
                if (token.isEmpty()) {
                    return Optional.empty();
                }
                response = headManifest(manifestUri, token.get());
            }
            if (response.statusCode() != 200) {
                log.debug("Registry {} answered {} for {}", reference.registry(), response.statusCode(),
                    reference.canonical());
                return Optional.empty();
            }
            return response.headers().firstValue(DIGEST_HEADER).filter(d -> !d.isBlank());
        } catch (InterruptedException e) {
            // Shutdown, not a registry verdict: restore the flag so the sweep's thread can stop.
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Could not resolve digest for {}: {}", reference.canonical(), e.getMessage());
            return Optional.empty();
        }
    }

    private HttpResponse<String> headManifest(URI uri, String bearerToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", ACCEPT_MANIFEST_TYPES);
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A pull-only token for exactly this repository, from the realm the registry's own challenge names — which
     * is what makes this work for Docker Hub, ghcr.io and lscr.io without knowing any of them.
     */
    private Optional<String> fetchAnonymousToken(HttpResponse<String> challenge, ImageReference reference)
            throws Exception {
        Optional<String> header = challenge.headers().firstValue("WWW-Authenticate");
        if (header.isEmpty()) {
            return Optional.empty();
        }
        Matcher realm = REALM.matcher(header.get());
        if (!realm.find()) {
            return Optional.empty();
        }
        StringBuilder tokenUri = new StringBuilder(realm.group(1))
            .append("?scope=").append(encode("repository:" + reference.repository() + ":pull"));
        Matcher service = SERVICE.matcher(header.get());
        if (service.find()) {
            tokenUri.append("&service=").append(encode(service.group(1)));
        }

        HttpResponse<String> response = httpClient.send(
            HttpRequest.newBuilder(URI.create(tokenUri.toString())).GET().timeout(REQUEST_TIMEOUT).build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return Optional.empty();
        }
        // Registries name it "token"; some (ghcr.io) also answer "access_token" for the same value.
        var body = objectMapper.readTree(response.body());
        String token = body.path("token").asText(body.path("access_token").asText(""));
        return token.isBlank() ? Optional.empty() : Optional.of(token);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
