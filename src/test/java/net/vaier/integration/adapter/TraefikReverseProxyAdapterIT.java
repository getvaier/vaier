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
    void deleteRoute_clearsLaunchpadAlias_soRepublishDoesNotResurrectIt() {
        // Bug: set a display name (launchpad alias), delete the machine/service, then re-publish the
        // same FQDN — the alias must not come back. Sidecar metadata is keyed by router name, which
        // is deterministic from the FQDN, so a leftover entry re-applies to the new route.
        adapter.addReverseProxyRoute("asd.example.com", "10.13.13.2", 8080, false, null);
        adapter.setRouteLaunchpadAlias("asd.example.com", null, "HUE");
        assertThat(adapter.getReverseProxyRoutes().getFirst().getLaunchpadAlias()).isEqualTo("HUE");

        adapter.deleteReverseProxyRouteByDnsName("asd.example.com");
        adapter.addReverseProxyRoute("asd.example.com", "10.13.13.2", 8080, false, null);

        assertThat(adapter.getReverseProxyRoutes().getFirst().getLaunchpadAlias()).isNull();
    }

    @Test
    void deleteRoute_clearsHiddenDirectUrlAndVersionSidecars() {
        adapter.addReverseProxyRoute("asd.example.com", "10.13.13.2", 8080, false, null);
        adapter.setRouteHiddenFromLaunchpad("asd.example.com", null, true);
        adapter.setRouteDirectUrlDisabled("asd.example.com", null, true);
        adapter.setRouteVersionEndpoint("asd.example.com", null, "/version", "value");

        adapter.deleteReverseProxyRouteByDnsName("asd.example.com");
        adapter.addReverseProxyRoute("asd.example.com", "10.13.13.2", 8080, false, null);

        ReverseProxyRoute r = adapter.getReverseProxyRoutes().getFirst();
        assertThat(r.isHiddenFromLaunchpad()).isFalse();
        assertThat(r.isDirectUrlDisabled()).isFalse();
        assertThat(r.getVersionEndpoint()).isNull();
    }

    @Test
    void deletePathScopedRoute_clearsOwnAliasButNotSiblings_andNoResurrectOnRepublish() {
        // Path-based delete goes through deleteReverseProxyRoute(routerName) directly. It must clear
        // only the deleted route's sidecar metadata, leave path-siblings on the same FQDN untouched,
        // and not resurrect on re-publish.
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null, "/a");
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null, "/b");
        adapter.setRouteLaunchpadAlias("app.example.com", "/a", "Alpha");
        adapter.setRouteLaunchpadAlias("app.example.com", "/b", "Bravo");

        adapter.deleteReverseProxyRoute(ReverseProxyRoute.routerName("app.example.com", "/a"));

        assertThat(adapter.getReverseProxyRoutes())
                .filteredOn(r -> "/b".equals(r.getPathPrefix()))
                .singleElement()
                .satisfies(r -> assertThat(r.getLaunchpadAlias()).isEqualTo("Bravo"));

        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null, "/a");
        assertThat(adapter.getReverseProxyRoutes())
                .filteredOn(r -> "/a".equals(r.getPathPrefix()))
                .allSatisfy(r -> assertThat(r.getLaunchpadAlias()).isNull());
    }

    @Test
    void deleteHostOnlyRouteByRouterName_clearsFqdnKeyedLanMarker() {
        // Rollback paths delete host-only routes via deleteReverseProxyRoute(routerName), not by
        // DNS name. The FQDN-keyed LAN-service marker must still be cleared — derived from the
        // route's own rule, not from the caller.
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.1.50", 80, "http", false, false, null);
        assertThat(adapter.getReverseProxyRoutes().getFirst().isLanService()).isTrue();

        adapter.deleteReverseProxyRoute(ReverseProxyRoute.routerName("nas.example.com", null));
        adapter.addReverseProxyRoute("nas.example.com", "10.13.13.2", 8080, false, null);

        assertThat(adapter.getReverseProxyRoutes().getFirst().isLanService()).isFalse();
    }

    @Test
    void deleteRoute_removesSidecarEntryEvenWithExplicitNullValue() throws IOException {
        // A sidecar entry with an explicit null value (manual edits / partial writes) must still be
        // removed on delete. Map.remove returns null for an explicit-null value, so a naive
        // remove()-non-null check would leave the stale key behind.
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        String routerName = ReverseProxyRoute.routerName("app.example.com", null);
        Path cfg = tempDir.resolve("remote-apps.yml");
        Files.writeString(cfg, Files.readString(cfg)
                + "\nx-vaier-launchpad-alias:\n  " + routerName + ": null\n");

        adapter.deleteReverseProxyRoute(routerName);

        assertThat(Files.readString(cfg)).doesNotContain("x-vaier-launchpad-alias");
    }

    @Test
    void deleteRoute_removesAllDuplicateListEntries_andToleratesNullElements() throws IOException {
        // Hand-edited YAML may contain duplicate or null list entries. Delete must not throw on a
        // null element and must remove every occurrence, not just the first.
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        String routerName = ReverseProxyRoute.routerName("app.example.com", null);
        Path cfg = tempDir.resolve("remote-apps.yml");
        Files.writeString(cfg, Files.readString(cfg)
                + "\nx-vaier-hidden-from-launchpad:\n  - " + routerName + "\n  - " + routerName + "\n  - null\n");

        adapter.deleteReverseProxyRoute(routerName);
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        assertThat(adapter.getReverseProxyRoutes().getFirst().isHiddenFromLaunchpad()).isFalse();
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
