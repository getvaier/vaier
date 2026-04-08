package net.vaier.adapter.driven;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DockerHubRegistryAdapterTest {

    HttpClient httpClient;
    DockerHubRegistryAdapter adapter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        adapter = new DockerHubRegistryAdapter(httpClient);
    }

    @Test
    void normalizeImage_bareNameBecomesLibraryPrefix() {
        assertThat(DockerHubRegistryAdapter.normalizeImage("nginx"))
                .isEqualTo("library/nginx");
    }

    @Test
    void normalizeImage_namespacedImageUnchanged() {
        assertThat(DockerHubRegistryAdapter.normalizeImage("myuser/myapp"))
                .isEqualTo("myuser/myapp");
    }

    @Test
    void normalizeImage_stripTagFromImage() {
        assertThat(DockerHubRegistryAdapter.normalizeImage("nginx:1.25"))
                .isEqualTo("library/nginx");
    }

    @Test
    void getRemoteDigest_successfulResponse_returnsDigest() throws Exception {
        mockTokenResponse("{\"token\":\"test-token\"}");
        mockManifestResponse(200, "sha256:abc123def456");

        Optional<String> digest = adapter.getRemoteDigest("nginx", "1.25");

        assertThat(digest).contains("sha256:abc123def456");
    }

    @Test
    void getRemoteDigest_manifestNotFound_returnsEmpty() throws Exception {
        mockTokenResponse("{\"token\":\"test-token\"}");
        mockManifestResponse(404, null);

        Optional<String> digest = adapter.getRemoteDigest("nginx", "nonexistent");

        assertThat(digest).isEmpty();
    }

    @Test
    void getRemoteDigest_nonDockerHubImage_returnsEmpty() {
        Optional<String> digest = adapter.getRemoteDigest("ghcr.io/owner/repo", "latest");

        assertThat(digest).isEmpty();
    }

    @Test
    void getRemoteDigest_httpException_returnsEmpty() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new RuntimeException("connection refused"));

        Optional<String> digest = adapter.getRemoteDigest("nginx", "1.25");

        assertThat(digest).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void mockTokenResponse(String body) throws Exception {
        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn(body);

        HttpResponse<String> manifestResponse = mock(HttpResponse.class);
        when(manifestResponse.statusCode()).thenReturn(200);
        when(manifestResponse.headers()).thenReturn(HttpHeaders.of(
                Map.of("docker-content-digest", List.of("sha256:default")),
                (a, b) -> true));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tokenResponse);
    }

    @SuppressWarnings("unchecked")
    private void mockManifestResponse(int statusCode, String digest) throws Exception {
        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"token\":\"test-token\"}");

        HttpResponse<String> manifestResponse = mock(HttpResponse.class);
        when(manifestResponse.statusCode()).thenReturn(statusCode);
        if (digest != null) {
            when(manifestResponse.headers()).thenReturn(HttpHeaders.of(
                    Map.of("docker-content-digest", List.of(digest)),
                    (a, b) -> true));
        }

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tokenResponse)
                .thenReturn(manifestResponse);
    }
}
