package net.vaier.integration.adapter;

import net.vaier.adapter.driven.TraefikReverseProxyAdapter;
import net.vaier.config.ServiceNames;
import net.vaier.domain.ReverseProxyRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for TraefikReverseProxyAdapter against a real temp directory.
 * Covers multi-step sequences that unit tests don't address.
 */
class TraefikReverseProxyAdapterIT {

    @TempDir
    Path tempDir;

    TraefikReverseProxyAdapter adapter;

    @BeforeEach
    void setUp() {
        String configFilePath = tempDir.resolve("remote-apps.yml").toString();
        adapter = new TraefikReverseProxyAdapter(configFilePath, "http://localhost:19999", "example.com");
    }

    @Test
    void addRoute_withRootRedirectPath_writesRedirectMiddleware() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, "/dashboard");

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("redirectRegex");
        assertThat(content).contains("/dashboard");
        assertThat(content).contains("app-example-com-redirect");
    }

    @Test
    void addRoute_withAuthAndRedirect_writesBothMiddlewares() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, true, "/dashboard");

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains(ServiceNames.AUTH_MIDDLEWARE);
        assertThat(content).contains("redirectRegex");
    }

    @Test
    void addRoute_thenDelete_removesRouterAndService() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        adapter.deleteReverseProxyRouteByDnsName("app.example.com");

        // Non-existent Traefik API → falls back to file routes only
        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        assertThat(routes).isEmpty();
    }

    @Test
    void addTwoRoutes_deleteOne_theOtherSurvives() {
        adapter.addReverseProxyRoute("app1.example.com", "10.13.13.2", 8080, false, null);
        adapter.addReverseProxyRoute("app2.example.com", "10.13.13.3", 9090, false, null);

        adapter.deleteReverseProxyRouteByDnsName("app1.example.com");

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        assertThat(routes).hasSize(1);
        assertThat(routes.getFirst().getDomainName()).isEqualTo("app2.example.com");
    }

    @Test
    void deleteRoute_throwsWhenRouterNotFound() {
        assertThatThrownBy(() -> adapter.deleteReverseProxyRouteByDnsName("nonexistent.example.com"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void setRouteAuthentication_addsAuthMiddleware() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        adapter.setRouteAuthentication("app.example.com", true);

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        assertThat(routes.getFirst().getMiddlewares()).contains(ServiceNames.AUTH_MIDDLEWARE);
    }

    @Test
    void setRouteAuthentication_removesAuthMiddleware() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, true, null);

        adapter.setRouteAuthentication("app.example.com", false);

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        List<String> middlewares = routes.getFirst().getMiddlewares();
        assertThat(middlewares == null || !middlewares.contains(ServiceNames.AUTH_MIDDLEWARE)).isTrue();
    }

    @Test
    void multiCycleAddDelete_yamlRemainsValid() {
        adapter.addReverseProxyRoute("a.example.com", "10.13.13.2", 8080, false, null);
        adapter.addReverseProxyRoute("b.example.com", "10.13.13.3", 9090, false, null);
        adapter.deleteReverseProxyRouteByDnsName("a.example.com");
        adapter.addReverseProxyRoute("c.example.com", "10.13.13.4", 7070, true, null);
        adapter.deleteReverseProxyRouteByDnsName("c.example.com");

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        assertThat(routes).hasSize(1);
        assertThat(routes.getFirst().getDomainName()).isEqualTo("b.example.com");
    }

    @Test
    void ignoreService_persistedAcrossNewAdapterInstance() {
        adapter.ignoreService("peer1:myapp:8080");

        TraefikReverseProxyAdapter adapter2 = new TraefikReverseProxyAdapter(
                tempDir.resolve("remote-apps.yml").toString(), "http://localhost:19999", "example.com");

        Set<String> ignored = adapter2.getIgnoredServiceKeys();
        assertThat(ignored).contains("peer1:myapp:8080");
    }

    @Test
    void ignoreAndUnignore_serviceKeyRemovedFromSet() {
        adapter.ignoreService("peer1:myapp:8080");
        adapter.unignoreService("peer1:myapp:8080");

        assertThat(adapter.getIgnoredServiceKeys()).doesNotContain("peer1:myapp:8080");
    }

    @Test
    void addRouteAfterIgnore_bothPersistedTogether() {
        adapter.ignoreService("peer1:myapp:8080");
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        TraefikReverseProxyAdapter freshAdapter = new TraefikReverseProxyAdapter(
                tempDir.resolve("remote-apps.yml").toString(), "http://localhost:19999", "example.com");

        assertThat(freshAdapter.getIgnoredServiceKeys()).contains("peer1:myapp:8080");
        assertThat(freshAdapter.getReverseProxyRoutes()).hasSize(1);
    }
}
