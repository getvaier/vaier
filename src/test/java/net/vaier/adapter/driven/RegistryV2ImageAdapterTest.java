package net.vaier.adapter.driven;

import net.vaier.domain.ImageReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegistryV2ImageAdapterTest {

    private HttpClient httpClient;
    private MutableClock clock;
    private final List<HttpRequest> sent = new ArrayList<>();

    /** A clock the test can wind forward, to prove the cache's 24h TTL without sleeping. */
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-17T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        clock = new MutableClock();
        sent.clear();
    }

    private RegistryV2ImageAdapter adapter() {
        return new RegistryV2ImageAdapter(httpClient, clock);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, Map<String, List<String>> headers, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (a, b) -> true));
        when(response.body()).thenReturn(body);
        return response;
    }

    /** Answers each send() in order, recording the requests so the flow can be asserted. */
    private void respondInOrder(HttpResponse<String>... responses) throws Exception {
        when(httpClient.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            sent.add(request);
            return responses[Math.min(sent.size() - 1, responses.length - 1)];
        });
    }

    private static final Map<String, List<String>> UNAUTHORIZED = Map.of(
        "WWW-Authenticate",
        List.of("Bearer realm=\"https://auth.docker.io/token\",service=\"registry.docker.io\""));

    @Test
    void resolvesADigestOverTheAnonymousBearerTokenFlow() throws Exception {
        respondInOrder(
            response(401, UNAUTHORIZED, ""),
            response(200, Map.of(), "{\"token\":\"tok-123\"}"),
            response(200, Map.of("Docker-Content-Digest", List.of("sha256:served")), ""));

        Optional<String> digest = adapter().resolveDigest(
            ImageReference.parse("vaultwarden/server:latest").orElseThrow());

        assertThat(digest).contains("sha256:served");
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0).uri())
            .isEqualTo(URI.create("https://registry-1.docker.io/v2/vaultwarden/server/manifests/latest"));
        assertThat(sent.get(0).method()).isEqualTo("HEAD");
        // The token is requested for exactly this repository, pull-only: Vaier never asks for write scope.
        assertThat(sent.get(1).uri().toString())
            .contains("https://auth.docker.io/token")
            .contains("scope=repository%3Avaultwarden%2Fserver%3Apull")
            .contains("service=registry.docker.io");
        assertThat(sent.get(2).headers().firstValue("Authorization")).contains("Bearer tok-123");
    }

    @Test
    void asksForBothManifestListAndOciIndexTypesSoAMultiArchImageResolves() throws Exception {
        // Without these Accept types a multi-arch registry answers with a per-arch manifest whose digest is
        // NOT the one the local RepoDigest records — every multi-arch image would read as outdated forever.
        respondInOrder(response(200, Map.of("Docker-Content-Digest", List.of("sha256:served")), ""));

        adapter().resolveDigest(ImageReference.parse("vaultwarden/server:latest").orElseThrow());

        String accept = String.join(",", sent.get(0).headers().allValues("Accept"));
        assertThat(accept)
            .contains("application/vnd.docker.distribution.manifest.list.v2+json")
            .contains("application/vnd.oci.image.index.v1+json")
            .contains("application/vnd.docker.distribution.manifest.v2+json")
            .contains("application/vnd.oci.image.manifest.v1+json");
    }

    @Test
    void resolvesAgainstANonDockerHubRegistryUsingItsOwnRealm() throws Exception {
        respondInOrder(
            response(401, Map.of("WWW-Authenticate",
                List.of("Bearer realm=\"https://ghcr.io/token\",service=\"ghcr.io\"")), ""),
            response(200, Map.of(), "{\"token\":\"ghcr-tok\"}"),
            response(200, Map.of("Docker-Content-Digest", List.of("sha256:ghcr")), ""));

        Optional<String> digest = adapter().resolveDigest(
            ImageReference.parse("ghcr.io/home-assistant/home-assistant:2025.7").orElseThrow());

        assertThat(digest).contains("sha256:ghcr");
        assertThat(sent.get(0).uri())
            .isEqualTo(URI.create("https://ghcr.io/v2/home-assistant/home-assistant/manifests/2025.7"));
        assertThat(sent.get(1).uri().toString()).startsWith("https://ghcr.io/token");
    }

    @Test
    void resolvesAnLscrImageAtItsOwnRegistry() throws Exception {
        respondInOrder(response(200, Map.of("Docker-Content-Digest", List.of("sha256:ls")), ""));

        Optional<String> digest = adapter().resolveDigest(
            ImageReference.parse("lscr.io/linuxserver/wireguard:1.0.20250521-r1-ls110").orElseThrow());

        assertThat(digest).contains("sha256:ls");
        assertThat(sent.get(0).uri()).isEqualTo(
            URI.create("https://lscr.io/v2/linuxserver/wireguard/manifests/1.0.20250521-r1-ls110"));
    }

    @Test
    void aRegistryThatAnswersWithoutTheDigestHeaderResolvesToEmpty() throws Exception {
        respondInOrder(response(200, Map.of(), ""));

        assertThat(adapter().resolveDigest(ImageReference.parse("redis:7.2").orElseThrow())).isEmpty();
    }

    @Test
    void anUnknownTagResolvesToEmptyRatherThanThrowing() throws Exception {
        respondInOrder(response(404, Map.of(), "not found"));

        assertThat(adapter().resolveDigest(ImageReference.parse("redis:nope").orElseThrow())).isEmpty();
    }

    @Test
    void aRateLimitedRegistryResolvesToEmpty() throws Exception {
        respondInOrder(response(429, Map.of(), "too many requests"));

        assertThat(adapter().resolveDigest(ImageReference.parse("redis:7.2").orElseThrow())).isEmpty();
    }

    @Test
    void noEgressToTheRegistryResolvesToEmptyRatherThanThrowing() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("network unreachable"));

        assertThat(adapter().resolveDigest(ImageReference.parse("redis:7.2").orElseThrow())).isEmpty();
    }

    @Test
    void aStillAuthorisedRefusalResolvesToEmptyRatherThanLooping() throws Exception {
        // 401 → token → still 401. Answer empty instead of retrying forever.
        respondInOrder(
            response(401, UNAUTHORIZED, ""),
            response(200, Map.of(), "{\"token\":\"tok\"}"),
            response(401, UNAUTHORIZED, ""));

        assertThat(adapter().resolveDigest(ImageReference.parse("private/repo:1").orElseThrow())).isEmpty();
        assertThat(sent).hasSize(3);
    }

    @Test
    void servesARepeatLookupFromCacheWithoutTouchingTheRegistryAgain() throws Exception {
        // Anonymous Docker Hub allows ~100 manifest requests per six hours — a cached answer is not an
        // optimisation here, it is what keeps Vaier under the limit.
        respondInOrder(response(200, Map.of("Docker-Content-Digest", List.of("sha256:served")), ""));
        RegistryV2ImageAdapter adapter = adapter();
        ImageReference ref = ImageReference.parse("redis:7.2").orElseThrow();

        assertThat(adapter.resolveDigest(ref)).contains("sha256:served");
        assertThat(adapter.resolveDigest(ref)).contains("sha256:served");

        assertThat(sent).hasSize(1);
    }

    @Test
    void asksTheRegistryAgainOnceTheCachedAnswerIsADayOld() throws Exception {
        respondInOrder(response(200, Map.of("Docker-Content-Digest", List.of("sha256:served")), ""));
        RegistryV2ImageAdapter adapter = adapter();
        ImageReference ref = ImageReference.parse("redis:7.2").orElseThrow();
        adapter.resolveDigest(ref);

        clock.advance(Duration.ofHours(25));
        adapter.resolveDigest(ref);

        assertThat(sent).hasSize(2);
    }

    @Test
    void cachesPerImageReferenceNotPerRepository() throws Exception {
        respondInOrder(response(200, Map.of("Docker-Content-Digest", List.of("sha256:served")), ""));
        RegistryV2ImageAdapter adapter = adapter();

        adapter.resolveDigest(ImageReference.parse("redis:7.2").orElseThrow());
        adapter.resolveDigest(ImageReference.parse("redis:7.4").orElseThrow());

        assertThat(sent).hasSize(2);
    }

    @Test
    void doesNotCacheAFailureSoATransientOutageIsRetriedOnTheNextSweep() throws Exception {
        // Caching "unknown" for 24h would mean one blip blinds Vaier for a day.
        when(httpClient.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return response(500, Map.of(), "");
        });
        RegistryV2ImageAdapter adapter = adapter();
        ImageReference ref = ImageReference.parse("redis:7.2").orElseThrow();

        adapter.resolveDigest(ref);
        adapter.resolveDigest(ref);

        assertThat(sent).hasSize(2);
    }
}
