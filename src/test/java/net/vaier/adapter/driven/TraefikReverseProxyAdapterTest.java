package net.vaier.adapter.driven;

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
        assertThat(content).contains(ServiceNames.AUTH_MIDDLEWARE);
    }

    @Test
    void addReverseProxyRoute_withoutAuthDoesNotAddAuthMiddleware() throws IOException {
        adapter.addReverseProxyRoute("open.example.com", "10.13.13.2", 8080, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).doesNotContain(ServiceNames.AUTH_MIDDLEWARE);
    }

    @Test
    void addReverseProxyRoute_setsWebsecureEntrypoint() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains(ServiceNames.ENTRY_POINT_WEBSECURE);
    }

    @Test
    void addReverseProxyRoute_setsLetsEncryptCertResolver() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains(ServiceNames.CERT_RESOLVER);
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
        assertThat(content).contains(ServiceNames.AUTH_MIDDLEWARE);
    }

    @Test
    void setRouteAuthentication_disablesAuthOnExistingRoute() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, true, null);

        adapter.setRouteAuthentication("app.example.com", false);

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        assertThat(routes.getFirst().getAuthInfo()).isNull();
    }

    // --- setRouteDirectUrlDisabled ---

    @Test
    void setRouteDirectUrlDisabled_persistsFlagThatRoundTripsViaGetRoutes() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        adapter.setRouteDirectUrlDisabled("app.example.com", true);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.isDirectUrlDisabled()).isTrue();
    }

    @Test
    void setRouteDirectUrlDisabled_false_clearsFlag() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.setRouteDirectUrlDisabled("app.example.com", true);

        adapter.setRouteDirectUrlDisabled("app.example.com", false);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.isDirectUrlDisabled()).isFalse();
    }

    @Test
    void getReverseProxyRoutes_unsetDirectUrlDisabled_defaultsToFalse() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.isDirectUrlDisabled()).isFalse();
    }

    @Test
    void setRouteDirectUrlDisabled_persistsAcrossAdapterInstances() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.setRouteDirectUrlDisabled("app.example.com", true);

        var adapter2 = new TraefikReverseProxyAdapter(
            tempDir.resolve("remote-apps.yml").toString(), "http://localhost:19999", "example.com");

        assertThat(adapter2.getReverseProxyRoutes().getFirst().isDirectUrlDisabled()).isTrue();
    }

    // --- getReverseProxyRoutes with empty file ---

    @Test
    void getReverseProxyRoutes_returnsEmptyListWhenFileIsEmpty() {
        assertThat(adapter.getReverseProxyRoutes()).isEmpty();
    }

    @Test
    void addReverseProxyRoute_configFileAlwaysStartsWithHttpKey() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content.stripLeading()).startsWith("http:");
    }

    // --- addLanReverseProxyRoute (#175) ---

    @Test
    void addLanReverseProxyRoute_writesHttpsBackendUrlForHttpsProtocol() throws IOException {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "https", false, false);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("url: https://192.168.3.50:5000");
    }

    @Test
    void addLanReverseProxyRoute_writesHttpBackendUrlForHttpProtocol() throws IOException {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "http", false, false);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("url: http://192.168.3.50:5000");
    }

    @Test
    void addLanReverseProxyRoute_persistsLanServiceMarkerInYaml() throws IOException {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "http", false, false);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("x-vaier-lan-service");
        assertThat(content).contains("nas.example.com");
    }

    @Test
    void addLanReverseProxyRoute_roundTripsAsLanTypedRoute() {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "https", false, false);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getDomainName()).isEqualTo("nas.example.com");
        assertThat(route.getAddress()).isEqualTo("192.168.3.50");
        assertThat(route.getPort()).isEqualTo(5000);
        assertThat(route.isLanService()).isTrue();
        assertThat(route.getProtocol()).isEqualTo("https");
    }

    @Test
    void addLanReverseProxyRoute_withRequiresAuth_addsAuthMiddleware() throws IOException {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "http", true, false);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains(ServiceNames.AUTH_MIDDLEWARE);
    }

    @Test
    void addLanReverseProxyRoute_withDirectUrlDisabled_persistsFlag() {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "http", false, true);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.isDirectUrlDisabled()).isTrue();
    }

    @Test
    void addReverseProxyRoute_nonLanRoute_isLanServiceIsFalseOnReadBack() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.isLanService()).isFalse();
    }

    // --- ForManagingIgnoredServices ---

    @Test
    void getIgnoredServiceKeys_emptyByDefault() {
        assertThat(adapter.getIgnoredServiceKeys()).isEmpty();
    }

    @Test
    void ignoreService_keyAppearsInIgnoredSet() {
        adapter.ignoreService("my-app");

        assertThat(adapter.getIgnoredServiceKeys()).containsExactly("my-app");
    }

    @Test
    void ignoreService_multipleKeysAllPersisted() {
        adapter.ignoreService("app-a");
        adapter.ignoreService("app-b");

        assertThat(adapter.getIgnoredServiceKeys()).containsExactlyInAnyOrder("app-a", "app-b");
    }

    @Test
    void ignoreService_persistedAcrossAdapterInstances() {
        adapter.ignoreService("my-app");

        // New adapter instance reading the same file
        var adapter2 = new TraefikReverseProxyAdapter(
            tempDir.resolve("remote-apps.yml").toString(), "http://localhost:19999", "example.com");
        assertThat(adapter2.getIgnoredServiceKeys()).containsExactly("my-app");
    }

    @Test
    void unignoreService_removesKeyFromIgnoredSet() {
        adapter.ignoreService("my-app");
        adapter.unignoreService("my-app");

        assertThat(adapter.getIgnoredServiceKeys()).isEmpty();
    }

    @Test
    void unignoreService_nonExistentKey_noError() {
        adapter.unignoreService("does-not-exist");

        assertThat(adapter.getIgnoredServiceKeys()).isEmpty();
    }

    @Test
    void ignoreService_doesNotAffectExistingRoutes() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.ignoreService("some-container");

        assertThat(adapter.getReverseProxyRoutes()).hasSize(1);
        assertThat(adapter.getIgnoredServiceKeys()).containsExactly("some-container");
    }
}
