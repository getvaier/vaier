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

    // --- auth modes (#305 step 3a) ---

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> loadYaml() throws IOException {
        try (var in = Files.newInputStream(tempDir.resolve("remote-apps.yml"))) {
            return new org.yaml.snakeyaml.Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> http() throws IOException {
        return (java.util.Map<String, Object>) loadYaml().get("http");
    }

    @Test
    void addReverseProxyRoute_socialMode_attachesTheTwoStageChainInOrder() throws IOException {
        adapter.addReverseProxyRoute("secure.example.com", "10.13.13.2", 8080,
            net.vaier.domain.AuthMode.SOCIAL, null, null);

        var routers = (java.util.Map<String, Object>) http().get("routers");
        var router = (java.util.Map<String, Object>) routers.get("secure-example-com-router");
        assertThat((List<String>) router.get("middlewares"))
            .containsExactly("oauth2-signin", "oauth2-authn", "vaier-authz", "vaier-errors");
    }

    @Test
    void addReverseProxyRoute_socialMode_definesTheProvenMiddlewares() throws IOException {
        adapter.addReverseProxyRoute("secure.example.com", "10.13.13.2", 8080,
            net.vaier.domain.AuthMode.SOCIAL, null, null);

        var middlewares = (java.util.Map<String, Object>) http().get("middlewares");

        var signin = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) middlewares.get("oauth2-signin")).get("errors");
        assertThat((List<String>) signin.get("status")).containsExactly("401");
        assertThat(signin.get("service")).isEqualTo("oauth2-proxy-svc");
        assertThat(signin.get("query")).isEqualTo("/oauth2/sign_in?rd={url}");

        var authn = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) middlewares.get("oauth2-authn")).get("forwardAuth");
        assertThat(authn.get("address")).isEqualTo("http://oauth2-proxy:4180/oauth2/auth");
        assertThat((List<String>) authn.get("authResponseHeaders"))
            .containsExactly("X-Auth-Request-Email", "X-Auth-Request-User", "X-Auth-Request-Name");

        var authz = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) middlewares.get("vaier-authz")).get("forwardAuth");
        assertThat(authz.get("address")).isEqualTo("http://vaier:8080/authz/verify");
        assertThat((List<String>) authz.get("authResponseHeaders"))
            .containsExactly("Remote-User", "Remote-Email", "Remote-Groups");
    }

    @Test
    void addReverseProxyRoute_socialMode_addsHigherPriorityOauth2EndpointsRouterForTheHost() throws IOException {
        adapter.addReverseProxyRoute("secure.example.com", "10.13.13.2", 8080,
            net.vaier.domain.AuthMode.SOCIAL, null, null);

        var routers = (java.util.Map<String, Object>) http().get("routers");
        var endpoints = (java.util.Map<String, Object>) routers.get("secure-example-com-oauth2-router");
        assertThat(endpoints).isNotNull();
        assertThat(endpoints.get("rule"))
            .isEqualTo("Host(`secure.example.com`) && PathPrefix(`/oauth2/`)");
        assertThat(endpoints.get("service")).isEqualTo("oauth2-proxy-svc");
        assertThat(endpoints.get("priority")).isEqualTo(100);

        var services = (java.util.Map<String, Object>) http().get("services");
        var svc = (java.util.Map<String, Object>) services.get("oauth2-proxy-svc");
        var servers = (List<java.util.Map<String, Object>>)
            ((java.util.Map<String, Object>) svc.get("loadBalancer")).get("servers");
        assertThat(servers.get(0).get("url")).isEqualTo("http://oauth2-proxy:4180");
    }

    @Test
    void addReverseProxyRoute_socialMode_roundTripsAsSocialViaGetRoutes() {
        adapter.addReverseProxyRoute("secure.example.com", "10.13.13.2", 8080,
            net.vaier.domain.AuthMode.SOCIAL, null, null);

        // The /oauth2/ endpoints router carries no backend the published list cares about; the
        // primary route must report SOCIAL.
        ReverseProxyRoute primary = adapter.getReverseProxyRoutes().stream()
            .filter(r -> r.getName().equals("secure-example-com-router"))
            .findFirst().orElseThrow();
        assertThat(primary.authMode()).isEqualTo(net.vaier.domain.AuthMode.SOCIAL);
    }

    @Test
    void setRouteAuthMode_switchesAutheliaRouteToSocial_strippingTheOldChain() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, true, null);

        adapter.setRouteAuthMode("app.example.com", null, net.vaier.domain.AuthMode.SOCIAL);

        var routers = (java.util.Map<String, Object>) http().get("routers");
        var router = (java.util.Map<String, Object>) routers.get("app-example-com-router");
        assertThat((List<String>) router.get("middlewares"))
            .containsExactly("oauth2-signin", "oauth2-authn", "vaier-authz", "vaier-errors")
            .doesNotContain("auth-middleware");
        assertThat(routers).containsKey("app-example-com-oauth2-router");
    }

    @Test
    void setRouteAuthMode_switchesSocialRouteBackToNone() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080,
            net.vaier.domain.AuthMode.SOCIAL, null, null);

        adapter.setRouteAuthMode("app.example.com", null, net.vaier.domain.AuthMode.NONE);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().stream()
            .filter(r -> r.getName().equals("app-example-com-router")).findFirst().orElseThrow();
        assertThat(route.authMode()).isEqualTo(net.vaier.domain.AuthMode.NONE);
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

    // --- setRouteHiddenFromLaunchpad ---

    @Test
    void setRouteHiddenFromLaunchpad_persistsFlagThatRoundTripsViaGetRoutes() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        adapter.setRouteHiddenFromLaunchpad("app.example.com", true);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.isHiddenFromLaunchpad()).isTrue();
    }

    @Test
    void setRouteHiddenFromLaunchpad_false_clearsFlag() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.setRouteHiddenFromLaunchpad("app.example.com", true);

        adapter.setRouteHiddenFromLaunchpad("app.example.com", false);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.isHiddenFromLaunchpad()).isFalse();
    }

    @Test
    void getReverseProxyRoutes_unsetHiddenFromLaunchpad_defaultsToFalse() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.isHiddenFromLaunchpad()).isFalse();
    }

    @Test
    void setRouteHiddenFromLaunchpad_persistsAcrossAdapterInstances() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.setRouteHiddenFromLaunchpad("app.example.com", true);

        var adapter2 = new TraefikReverseProxyAdapter(
            tempDir.resolve("remote-apps.yml").toString(), "http://localhost:19999", "example.com");

        assertThat(adapter2.getReverseProxyRoutes().getFirst().isHiddenFromLaunchpad()).isTrue();
    }

    @Test
    void setRouteHiddenFromLaunchpad_pathBasedRoute_targetsCorrectSibling() {
        adapter.addReverseProxyRoute("svc.example.com", "10.13.13.2", 8080, false, null, "/grafana");
        adapter.addReverseProxyRoute("svc.example.com", "10.13.13.2", 9090, false, null, "/prometheus");

        adapter.setRouteHiddenFromLaunchpad("svc.example.com", "/grafana", true);

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        ReverseProxyRoute grafana = routes.stream().filter(r -> "/grafana".equals(r.getPathPrefix())).findFirst().orElseThrow();
        ReverseProxyRoute prometheus = routes.stream().filter(r -> "/prometheus".equals(r.getPathPrefix())).findFirst().orElseThrow();
        assertThat(grafana.isHiddenFromLaunchpad()).isTrue();
        assertThat(prometheus.isHiddenFromLaunchpad()).isFalse();
    }

    // --- setRouteLaunchpadAlias ---

    @Test
    void setRouteLaunchpadAlias_persistsAliasThatRoundTripsViaGetRoutes() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        adapter.setRouteLaunchpadAlias("app.example.com", "Grafana Prod");

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getLaunchpadAlias()).isEqualTo("Grafana Prod");
    }

    @Test
    void setRouteLaunchpadAlias_null_clearsAlias() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.setRouteLaunchpadAlias("app.example.com", "Grafana Prod");

        adapter.setRouteLaunchpadAlias("app.example.com", null);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getLaunchpadAlias()).isNull();
    }

    @Test
    void getReverseProxyRoutes_unsetLaunchpadAlias_defaultsToNull() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getLaunchpadAlias()).isNull();
    }

    @Test
    void setRouteLaunchpadAlias_persistsAcrossAdapterInstances() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.setRouteLaunchpadAlias("app.example.com", "My App");

        var adapter2 = new TraefikReverseProxyAdapter(
            tempDir.resolve("remote-apps.yml").toString(), "http://localhost:19999", "example.com");

        assertThat(adapter2.getReverseProxyRoutes().getFirst().getLaunchpadAlias()).isEqualTo("My App");
    }

    @Test
    void setRouteLaunchpadAlias_pathBasedRoute_targetsCorrectSibling() {
        adapter.addReverseProxyRoute("svc.example.com", "10.13.13.2", 8080, false, null, "/grafana");
        adapter.addReverseProxyRoute("svc.example.com", "10.13.13.2", 9090, false, null, "/prometheus");

        adapter.setRouteLaunchpadAlias("svc.example.com", "/grafana", "Grafana Prod");

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        ReverseProxyRoute grafana = routes.stream().filter(r -> "/grafana".equals(r.getPathPrefix())).findFirst().orElseThrow();
        ReverseProxyRoute prometheus = routes.stream().filter(r -> "/prometheus".equals(r.getPathPrefix())).findFirst().orElseThrow();
        assertThat(grafana.getLaunchpadAlias()).isEqualTo("Grafana Prod");
        assertThat(prometheus.getLaunchpadAlias()).isNull();
    }

    // --- setRouteVersionEndpoint ---

    @Test
    void setRouteVersionEndpoint_persistsEndpointAndPropertyThatRoundTripViaGetRoutes() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        adapter.setRouteVersionEndpoint("app.example.com", "/sys/metrics?name[]=system_info", "display");

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getVersionEndpoint()).isEqualTo("/sys/metrics?name[]=system_info");
        assertThat(route.getVersionProperty()).isEqualTo("display");
    }

    @Test
    void setRouteVersionEndpoint_blankEndpoint_clearsIt() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.setRouteVersionEndpoint("app.example.com", "/sys/metrics", "display");

        adapter.setRouteVersionEndpoint("app.example.com", "", "display");

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getVersionEndpoint()).isNull();
        assertThat(route.getVersionProperty()).isNull();
    }

    @Test
    void getReverseProxyRoutes_unsetVersionEndpoint_defaultsToNull() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getVersionEndpoint()).isNull();
        assertThat(route.getVersionProperty()).isNull();
    }

    @Test
    void setRouteVersionEndpoint_persistsAcrossAdapterInstances() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.setRouteVersionEndpoint("app.example.com", "/status", "build");

        var adapter2 = new TraefikReverseProxyAdapter(
            tempDir.resolve("remote-apps.yml").toString(), "http://localhost:19999", "example.com");

        ReverseProxyRoute route = adapter2.getReverseProxyRoutes().getFirst();
        assertThat(route.getVersionEndpoint()).isEqualTo("/status");
        assertThat(route.getVersionProperty()).isEqualTo("build");
    }

    @Test
    void setRouteVersionEndpoint_pathBasedRoute_targetsCorrectSibling() {
        adapter.addReverseProxyRoute("svc.example.com", "10.13.13.2", 8080, false, null, "/grafana");
        adapter.addReverseProxyRoute("svc.example.com", "10.13.13.2", 9090, false, null, "/prometheus");

        adapter.setRouteVersionEndpoint("svc.example.com", "/grafana", "/sys/metrics", "display");

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        ReverseProxyRoute grafana = routes.stream().filter(r -> "/grafana".equals(r.getPathPrefix())).findFirst().orElseThrow();
        ReverseProxyRoute prometheus = routes.stream().filter(r -> "/prometheus".equals(r.getPathPrefix())).findFirst().orElseThrow();
        assertThat(grafana.getVersionEndpoint()).isEqualTo("/sys/metrics");
        assertThat(prometheus.getVersionEndpoint()).isNull();
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
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "https", false, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("url: https://192.168.3.50:5000");
    }

    @Test
    void addLanReverseProxyRoute_writesHttpBackendUrlForHttpProtocol() throws IOException {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "http", false, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("url: http://192.168.3.50:5000");
    }

    @Test
    void addLanReverseProxyRoute_persistsLanServiceMarkerInYaml() throws IOException {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "http", false, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("x-vaier-lan-service");
        assertThat(content).contains("nas.example.com");
    }

    @Test
    void addLanReverseProxyRoute_roundTripsAsLanTypedRoute() {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "https", false, false, null);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getDomainName()).isEqualTo("nas.example.com");
        assertThat(route.getAddress()).isEqualTo("192.168.3.50");
        assertThat(route.getPort()).isEqualTo(5000);
        assertThat(route.isLanService()).isTrue();
        assertThat(route.getProtocol()).isEqualTo("https");
    }

    @Test
    void addLanReverseProxyRoute_withRequiresAuth_addsAuthMiddleware() throws IOException {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "http", true, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains(ServiceNames.AUTH_MIDDLEWARE);
    }

    @Test
    void addLanReverseProxyRoute_withDirectUrlDisabled_persistsFlag() {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "http", false, true, null);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.isDirectUrlDisabled()).isTrue();
    }

    @Test
    void addLanReverseProxyRoute_withRootRedirectPath_writesRedirectMiddleware() throws IOException {
        adapter.addLanReverseProxyRoute("app.example.com", "192.168.3.50", 3000, "http", false, false, "/builder/ui/");

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("redirectRegex");
        assertThat(content).contains("/builder/ui/");
        assertThat(content).contains("app-example-com-redirect");
    }

    @Test
    void addReverseProxyRoute_nonLanRoute_isLanServiceIsFalseOnReadBack() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.isLanService()).isFalse();
    }

    // --- pathPrefix (Phase B) ---

    @Test
    void addReverseProxyRoute_withPathPrefix_writesHostAndPathPrefixRule() throws IOException {
        adapter.addReverseProxyRoute("bmp.example.com", "10.13.13.2", 8081, false, null, "/auth");

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("Host(`bmp.example.com`) && PathPrefix(`/auth`)");
    }

    @Test
    void addReverseProxyRoute_withoutPathPrefix_writesHostOnlyRule() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("Host(`app.example.com`)");
        assertThat(content).doesNotContain("PathPrefix");
    }

    @Test
    void addReverseProxyRoute_twoPathPrefixesOnSameHost_coexistAsSeparateRouters() {
        adapter.addReverseProxyRoute("bmp.example.com", "10.13.13.2", 8081, false, null, "/auth");
        adapter.addReverseProxyRoute("bmp.example.com", "10.13.13.2", 8080, false, null, "/CorpoWebserver");

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        assertThat(routes).hasSize(2);
        assertThat(routes).extracting(ReverseProxyRoute::getPathPrefix)
            .containsExactlyInAnyOrder("/auth", "/CorpoWebserver");
        assertThat(routes).extracting(ReverseProxyRoute::getDomainName)
            .containsExactly("bmp.example.com", "bmp.example.com");
    }

    @Test
    void addReverseProxyRoute_rootAndPathPrefixedOnSameHost_coexistWithSeparateRulesAndBackends() throws IOException {
        // Host-only catch-all at port 8080; a more specific /auth route at port 8090.
        // Traefik resolves overlap by rule length — the path-prefixed router wins for /auth/*.
        adapter.addReverseProxyRoute("bmp.example.com", "10.13.13.2", 8080, false, null, null);
        adapter.addReverseProxyRoute("bmp.example.com", "10.13.13.2", 8090, false, null, "/auth");

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("Host(`bmp.example.com`)");
        assertThat(content).contains("Host(`bmp.example.com`) && PathPrefix(`/auth`)");
        assertThat(content).contains("url: http://10.13.13.2:8080");
        assertThat(content).contains("url: http://10.13.13.2:8090");

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        assertThat(routes).hasSize(2);
        assertThat(routes).extracting(ReverseProxyRoute::getPathPrefix)
            .containsExactlyInAnyOrder(null, "/auth");
        assertThat(routes).extracting(ReverseProxyRoute::getPort)
            .containsExactlyInAnyOrder(8080, 8090);
    }

    @Test
    void addReverseProxyRoute_withPathPrefix_routerAndServiceNamesIncludePathSlug() throws IOException {
        adapter.addReverseProxyRoute("bmp.example.com", "10.13.13.2", 8081, false, null, "/auth");

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("bmp-example-com-auth-router");
        assertThat(content).contains("bmp-example-com-auth-service");
    }

    @Test
    void addReverseProxyRoute_pathPrefixIsRoundTripped() {
        adapter.addReverseProxyRoute("bmp.example.com", "10.13.13.2", 8081, false, null, "/auth");

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getPathPrefix()).isEqualTo("/auth");
    }

    @Test
    void addLanReverseProxyRoute_withPathPrefix_writesHostAndPathPrefixRule() throws IOException {
        adapter.addLanReverseProxyRoute("app.example.com", "192.168.3.50", 3000, "http", false, false, null, "/builder/ui");

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("Host(`app.example.com`) && PathPrefix(`/builder/ui`)");
        assertThat(content).contains("app-example-com-builder-ui-router");
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

    // --- offline page (vaier-errors) infra ---

    @Test
    void addReverseProxyRoute_attachesVaierErrorsMiddlewareToRouter() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getMiddlewares()).contains(ServiceNames.ERROR_PAGES_MIDDLEWARE);
    }

    @Test
    void addLanReverseProxyRoute_attachesVaierErrorsMiddlewareToRouter() {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "http", false, false, null);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getMiddlewares()).contains(ServiceNames.ERROR_PAGES_MIDDLEWARE);
    }

    @Test
    void addReverseProxyRoute_definesErrorPagesServiceAndMiddleware() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        // error service points at the vaier container
        assertThat(content).contains(ServiceNames.ERROR_PAGES_SERVICE);
        assertThat(content).contains("http://vaier:8080");
        // errors middleware with the status list + query
        assertThat(content).contains(ServiceNames.ERROR_PAGES_MIDDLEWARE);
        assertThat(content).contains("/error-pages/{status}");
        assertThat(content).contains("502");
        assertThat(content).contains("503");
        assertThat(content).contains("504");
    }

    @Test
    void addReverseProxyRoute_errorPagesInfraIsIdempotent_singleServiceAcrossMultipleRoutes() {
        adapter.addReverseProxyRoute("app1.example.com", "10.13.13.2", 8080, false, null);
        adapter.addReverseProxyRoute("app2.example.com", "10.13.13.3", 9090, false, null);

        // Both routers reference vaier-errors; the shared service/middleware exist once.
        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        assertThat(routes).hasSize(2);
        assertThat(routes).allSatisfy(r ->
            assertThat(r.getMiddlewares()).contains(ServiceNames.ERROR_PAGES_MIDDLEWARE));
    }

    @Test
    void addReverseProxyRoute_withoutAuth_stillAttachesErrorsButNotAuth() throws IOException {
        adapter.addReverseProxyRoute("open.example.com", "10.13.13.2", 8080, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).doesNotContain(ServiceNames.AUTH_MIDDLEWARE);
        assertThat(content).contains(ServiceNames.ERROR_PAGES_MIDDLEWARE);
    }

    // --- backfill ---

    @Test
    void backfillErrorPages_addsVaierErrorsToRouterMissingIt_andCreatesInfra() throws IOException {
        // Pre-existing config: a router that predates the offline page, with auth + redirect
        // middlewares and x-vaier metadata that must all survive the backfill untouched.
        String preExisting = """
            http:
              routers:
                legacy-router:
                  rule: "Host(`legacy.example.com`)"
                  entryPoints:
                  - websecure
                  service: legacy-service
                  tls:
                    certResolver: letsencrypt
                  middlewares:
                  - auth-middleware
                  - legacy-redirect
              services:
                legacy-service:
                  loadBalancer:
                    servers:
                    - url: http://10.0.0.9:7000
              middlewares:
                auth-middleware:
                  forwardAuth:
                    address: http://authelia:9091/api/verify
                legacy-redirect:
                  redirectRegex:
                    regex: "^https://legacy\\\\.example\\\\.com/?$"
                    replacement: https://legacy.example.com/home
            x-vaier-launchpad-alias:
              legacy-router: My Legacy App
            """;
        Files.writeString(tempDir.resolve("remote-apps.yml"), preExisting);

        adapter.backfillErrorPages();

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        // vaier-errors attached to the legacy router
        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getMiddlewares()).contains(ServiceNames.ERROR_PAGES_MIDDLEWARE);
        // pre-existing middlewares are preserved on the router
        assertThat(route.getMiddlewares()).contains("auth-middleware", "legacy-redirect");
        // infra created
        assertThat(content).contains(ServiceNames.ERROR_PAGES_SERVICE);
        assertThat(content).contains("http://vaier:8080");
        assertThat(content).contains("/error-pages/{status}");
        // pre-existing server + metadata untouched
        assertThat(content).contains("http://10.0.0.9:7000");
        assertThat(content).contains("My Legacy App");
        assertThat(content).contains("auth-middleware");
        assertThat(content).contains("legacy-redirect");
    }

    @Test
    void backfillSocialMiddlewares_addsTheNameHeaderToAnOlderOauth2AuthnDefinition() throws IOException {
        // A config generated by an older Vaier: oauth2-authn forwards only email + user, no name.
        String preExisting = """
            http:
              routers:
                app-router:
                  rule: "Host(`app.example.com`)"
                  entryPoints:
                  - websecure
                  service: app-service
                  middlewares:
                  - oauth2-signin
                  - oauth2-authn
                  - vaier-authz
              services:
                app-service:
                  loadBalancer:
                    servers:
                    - url: http://10.0.0.9:7000
              middlewares:
                oauth2-authn:
                  forwardAuth:
                    address: http://oauth2-proxy:4180/oauth2/auth
                    trustForwardHeader: true
                    authResponseHeaders:
                    - X-Auth-Request-Email
                    - X-Auth-Request-User
            """;
        Files.writeString(tempDir.resolve("remote-apps.yml"), preExisting);

        adapter.backfillSocialMiddlewaresOnStartup();

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("X-Auth-Request-Name");
    }

    @Test
    void backfillSocialMiddlewares_isNoOpWhenNoSocialRouteExists() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        adapter.backfillSocialMiddlewaresOnStartup();

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).doesNotContain("oauth2-authn");
    }

    @Test
    void backfillErrorPages_isIdempotent_doesNotDuplicateMiddlewareOnRouter() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        adapter.backfillErrorPages();
        adapter.backfillErrorPages();

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        long count = route.getMiddlewares().stream()
            .filter(ServiceNames.ERROR_PAGES_MIDDLEWARE::equals).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void backfillErrorPages_emptyConfig_doesNotThrow() {
        adapter.backfillErrorPages();

        assertThat(adapter.getReverseProxyRoutes()).isEmpty();
    }
}
