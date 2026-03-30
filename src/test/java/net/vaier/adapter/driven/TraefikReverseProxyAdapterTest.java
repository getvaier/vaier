package net.vaier.adapter.driven;

import net.vaier.domain.ReverseProxyRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraefikReverseProxyAdapterTest {

    @TempDir Path tempDir;

    TraefikReverseProxyAdapter adapter;

    @BeforeEach
    void setUp() {
        String configFilePath = tempDir.resolve("remote-apps.yml").toString();
        // Use a non-existent Traefik API URL so getReverseProxyRoutes falls back to file
        adapter = new TraefikReverseProxyAdapter(configFilePath, "http://localhost:19999", "example.com");
    }

    // --- addReverseProxyRoute + getReverseProxyRoutes (file fallback) ---

    @Test
    void addReverseProxyRoute_writesRouteToConfigFile() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("app.example.com");
        assertThat(content).contains("10.13.13.2");
    }

    @Test
    void addReverseProxyRoute_canBeReadBackViaGetRoutes() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();

        assertThat(routes).hasSize(1);
        assertThat(routes.getFirst().getDomainName()).isEqualTo("app.example.com");
        assertThat(routes.getFirst().getAddress()).isEqualTo("10.13.13.2");
        assertThat(routes.getFirst().getPort()).isEqualTo(8080);
    }

    @Test
    void addReverseProxyRoute_multipleRoutesAreAllPersisted() {
        adapter.addReverseProxyRoute("app1.example.com", "10.13.13.2", 8080, false, null);
        adapter.addReverseProxyRoute("app2.example.com", "10.13.13.3", 9090, false, null);

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();

        assertThat(routes).hasSize(2);
        assertThat(routes).extracting(ReverseProxyRoute::getDomainName)
                .containsExactlyInAnyOrder("app1.example.com", "app2.example.com");
    }

    @Test
    void addReverseProxyRoute_withAuthAddsAuthMiddlewareToRouter() throws IOException {
        adapter.addReverseProxyRoute("secure.example.com", "10.13.13.2", 8080, true, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("auth-middleware");
    }

    @Test
    void addReverseProxyRoute_withoutAuthDoesNotAddAuthMiddleware() throws IOException {
        adapter.addReverseProxyRoute("open.example.com", "10.13.13.2", 8080, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).doesNotContain("auth-middleware");
    }

    @Test
    void addReverseProxyRoute_setsWebsecureEntrypoint() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("websecure");
    }

    @Test
    void addReverseProxyRoute_setsLetsEncryptCertResolver() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("letsencrypt");
    }

    // --- deleteReverseProxyRouteByDnsName ---

    @Test
    void deleteReverseProxyRouteByDnsName_removesRouteFromFile() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        adapter.deleteReverseProxyRouteByDnsName("app.example.com");

        assertThat(adapter.getReverseProxyRoutes()).isEmpty();
    }

    @Test
    void deleteReverseProxyRouteByDnsName_onlyRemovesTargetRoute() {
        adapter.addReverseProxyRoute("app1.example.com", "10.13.13.2", 8080, false, null);
        adapter.addReverseProxyRoute("app2.example.com", "10.13.13.3", 9090, false, null);

        adapter.deleteReverseProxyRouteByDnsName("app1.example.com");

        List<ReverseProxyRoute> remaining = adapter.getReverseProxyRoutes();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().getDomainName()).isEqualTo("app2.example.com");
    }

    @Test
    void deleteReverseProxyRouteByDnsName_throwsWhenRouteNotFound() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        assertThatThrownBy(() -> adapter.deleteReverseProxyRouteByDnsName("missing.example.com"))
                .isInstanceOf(RuntimeException.class);
    }

    // --- setRouteAuthentication ---

    @Test
    void setRouteAuthentication_enablesAuthOnExistingRoute() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        adapter.setRouteAuthentication("app.example.com", true);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("auth-middleware");
    }

    @Test
    void setRouteAuthentication_disablesAuthOnExistingRoute() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, true, null);

        adapter.setRouteAuthentication("app.example.com", false);

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        assertThat(routes.getFirst().getAuthInfo()).isNull();
    }

    // --- getReverseProxyRoutes with empty file ---

    @Test
    void getReverseProxyRoutes_returnsEmptyListWhenFileIsEmpty() {
        assertThat(adapter.getReverseProxyRoutes()).isEmpty();
    }
}
