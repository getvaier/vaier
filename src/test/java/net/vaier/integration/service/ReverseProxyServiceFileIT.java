package net.vaier.integration.service;

import net.vaier.adapter.driven.TraefikReverseProxyAdapter;
import net.vaier.application.AddReverseProxyRouteUseCase.ReverseProxyRouteUco;
import net.vaier.application.service.ReverseProxyService;
import net.vaier.config.ServiceNames;
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

/**
 * Service+file integration tests: wires ReverseProxyService against a real TraefikReverseProxyAdapter.
 * No Spring context or mocks.
 */
class ReverseProxyServiceFileIT {

    @TempDir
    Path tempDir;

    ReverseProxyService reverseProxyService;
    TraefikReverseProxyAdapter adapter;

    @BeforeEach
    void setUp() {
        String configFilePath = tempDir.resolve("remote-apps.yml").toString();
        adapter = new TraefikReverseProxyAdapter(configFilePath, "http://localhost:19999", "example.com");
        reverseProxyService = new ReverseProxyService(adapter);
    }

    @Test
    void addRoute_thenDeleteRoute_fileIsEmpty() {
        reverseProxyService.addReverseProxyRoute(
                new ReverseProxyRouteUco("app.example.com", "10.13.13.2", 8080, false));

        reverseProxyService.deleteReverseProxyRoute("app.example.com");

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        assertThat(routes).isEmpty();
    }

    @Test
    void addRouteWithAuth_thenDeleteRoute_authMiddlewareRemainsInFile() throws IOException {
        reverseProxyService.addReverseProxyRoute(
                new ReverseProxyRouteUco("app.example.com", "10.13.13.2", 8080, true));

        reverseProxyService.deleteReverseProxyRoute("app.example.com");

        // Adapter intentionally preserves shared middlewares (auth-middleware may be used by other routes)
        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        // Route is gone
        assertThat(adapter.getReverseProxyRoutes()).isEmpty();
        // The auth middleware definition may still be in the YAML — that is expected behaviour
        assertThat(content).doesNotContain("app.example.com");
    }

    @Test
    void addTwoRoutes_deleteBoth_fileHasNoRoutes() {
        reverseProxyService.addReverseProxyRoute(
                new ReverseProxyRouteUco("app1.example.com", "10.13.13.2", 8080, false));
        reverseProxyService.addReverseProxyRoute(
                new ReverseProxyRouteUco("app2.example.com", "10.13.13.3", 9090, false));

        reverseProxyService.deleteReverseProxyRoute("app1.example.com");
        reverseProxyService.deleteReverseProxyRoute("app2.example.com");

        assertThat(adapter.getReverseProxyRoutes()).isEmpty();
    }

    @Test
    void deleteNonExistentRoute_throwsRuntimeException() {
        assertThatThrownBy(() -> reverseProxyService.deleteReverseProxyRoute("nonexistent.example.com"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void addRoute_withRootRedirectPath_redirectWrittenToFile() throws IOException {
        reverseProxyService.addReverseProxyRoute(
                new ReverseProxyRouteUco("app.example.com", "10.13.13.2", 8080, false, "/dashboard"));

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("redirectRegex");
        assertThat(content).contains("/dashboard");
    }
}
