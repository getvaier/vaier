package net.vaier.adapter.driven;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.vaier.config.ServiceNames;
import net.vaier.domain.AuthMode;
import net.vaier.domain.ReverseProxyAudit;
import net.vaier.domain.ReverseProxyConfig;
import net.vaier.domain.ReverseProxyRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    void addReverseProxyRoute_withAuthAddsSocialMiddlewaresToRouter() throws IOException {
        // requiresAuth=true now maps to social login (Authelia is gone), so the route carries the
        // proven oauth2 middleware chain rather than the legacy auth-middleware.
        adapter.addReverseProxyRoute("secure.example.com", "10.13.13.2", 8080, true, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains(ServiceNames.VAIER_AUTHZ_MIDDLEWARE);
        assertThat(content).doesNotContain(ServiceNames.AUTH_MIDDLEWARE);
    }

    @Test
    void addReverseProxyRoute_withoutAuthDoesNotAddAuthMiddleware() throws IOException {
        adapter.addReverseProxyRoute("open.example.com", "10.13.13.2", 8080, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).doesNotContain(ServiceNames.AUTH_MIDDLEWARE);
        assertThat(content).doesNotContain(ServiceNames.VAIER_AUTHZ_MIDDLEWARE);
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
            .containsExactly("X-Auth-Request-Email", "X-Auth-Request-User", "X-Auth-Request-Name",
                    "X-Auth-Request-Connector", "X-Auth-Request-Connector-Uid");

        var authz = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) middlewares.get("vaier-authz")).get("forwardAuth");
        assertThat(authz.get("address")).isEqualTo("http://vaier:8080/authz/verify");
        assertThat((List<String>) authz.get("authResponseHeaders"))
            .containsExactly("Remote-User", "Remote-Email", "Remote-Groups", "Remote-Name");
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
        assertThat(content).contains(ServiceNames.VAIER_AUTHZ_MIDDLEWARE);
    }

    @Test
    void setRouteAuthentication_disablesAuthOnExistingRoute() throws IOException {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, true, null);

        adapter.setRouteAuthentication("app.example.com", false);

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();
        assertThat(routes.getFirst().getAuthInfo()).isNull();
    }

    // --- auth detection: forwardAuth is a mechanism, not a verdict (#341) ---

    /** A router plus the middleware definitions it references, as Vaier's own config file stores them. */
    private void writeConfigWithRouterMiddlewares(List<String> routerMiddlewares, String middlewareDefinitions)
            throws IOException {
        StringBuilder chain = new StringBuilder();
        for (String name : routerMiddlewares) {
            chain.append("  - ").append(name).append("\n");
        }
        Files.writeString(tempDir.resolve("remote-apps.yml"), """
            http:
              routers:
                app-example-com-router:
                  rule: "Host(`app.example.com`)"
                  entryPoints:
                  - websecure
                  service: app-service
                  middlewares:
            """ + chain.toString().indent(4) + """
              services:
                app-service:
                  loadBalancer:
                    servers:
                    - url: http://10.0.0.9:7000
              middlewares:
            """ + middlewareDefinitions.indent(4));
    }

    /** A CrowdSec-shaped bouncer: a forwardAuth middleware that rejects traffic but authenticates nobody. */
    private static final String CROWDSEC_DEFINITION = """
        crowdsec-bouncer:
          forwardAuth:
            address: http://crowdsec-bouncer:8080/api/v1/forwardAuth
            trustForwardHeader: true
        """;

    private static final String SOCIAL_CHAIN_DEFINITION = """
        oauth2-signin:
          forwardAuth:
            address: http://oauth2-proxy:4180/oauth2/sign_in
        oauth2-authn:
          forwardAuth:
            address: http://oauth2-proxy:4180/oauth2/auth
        vaier-authz:
          forwardAuth:
            address: http://vaier:8080/authz/verify
        """;

    @Test
    void getReverseProxyRoutes_nonAuthenticatingForwardAuthAlone_reportsThePublicServiceAsPublic() throws IOException {
        writeConfigWithRouterMiddlewares(List.of("crowdsec-bouncer"), CROWDSEC_DEFINITION);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();

        assertThat(route.getAuthInfo())
            .as("a bouncer in front of a deliberately public service must not make it report as gated")
            .isNull();
    }

    @Test
    void getReverseProxyRoutes_bouncerBeforeTheAuthChain_namesOauth2ProxyAsTheProvider() throws IOException {
        writeConfigWithRouterMiddlewares(
            List.of("crowdsec-bouncer", "oauth2-signin", "oauth2-authn", "vaier-authz"),
            CROWDSEC_DEFINITION + SOCIAL_CHAIN_DEFINITION);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();

        assertThat(route.getAuthInfo()).isNotNull();
        assertThat(route.getAuthInfo().getType()).isEqualTo("forwardAuth");
        assertThat(route.getAuthInfo().getUsername())
            .as("the provider label must come from the middleware that actually authenticates")
            .isEqualTo("oauth2-proxy");
    }

    @Test
    void getReverseProxyRoutes_bouncerAfterTheAuthChain_namesOauth2ProxyAsTheProvider() throws IOException {
        writeConfigWithRouterMiddlewares(
            List.of("oauth2-signin", "oauth2-authn", "vaier-authz", "crowdsec-bouncer"),
            SOCIAL_CHAIN_DEFINITION + CROWDSEC_DEFINITION);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();

        assertThat(route.getAuthInfo()).isNotNull();
        assertThat(route.getAuthInfo().getUsername()).isEqualTo("oauth2-proxy");
    }

    @Test
    void getReverseProxyRoutes_todaysStackShape_isUnchanged() throws IOException {
        writeConfigWithRouterMiddlewares(
            List.of("oauth2-signin", "oauth2-authn", "vaier-authz"), SOCIAL_CHAIN_DEFINITION);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();

        assertThat(route.getAuthInfo()).isNotNull();
        assertThat(route.getAuthInfo().getType()).isEqualTo("forwardAuth");
        assertThat(route.getAuthInfo().getUsername()).isEqualTo("oauth2-proxy");
    }

    @Test
    void getReverseProxyRoutes_authChainWithTraefiksProviderSuffix_isStillRecognised() throws IOException {
        writeConfigWithRouterMiddlewares(List.of("oauth2-authn@file"), """
            oauth2-authn@file:
              forwardAuth:
                address: http://oauth2-proxy:4180/oauth2/auth
            """);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();

        assertThat(route.getAuthInfo()).isNotNull();
        assertThat(route.getAuthInfo().getUsername()).isEqualTo("oauth2-proxy");
    }

    @Test
    void getReverseProxyRoutes_basicAuthIsStillAuthByType_whateverItsMiddlewareIsCalled() throws IOException {
        // basicAuth and digestAuth ARE authentication by type — unlike forwardAuth, which is only
        // a transport. That asymmetry is the whole point of #341, so the name is irrelevant here.
        writeConfigWithRouterMiddlewares(List.of("legacy-gate"), """
            legacy-gate:
              basicAuth:
                realm: Restricted
                users:
                - admin:hashedpassword
            """);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();

        assertThat(route.getAuthInfo()).isNotNull();
        assertThat(route.getAuthInfo().getType()).isEqualTo("basicAuth");
        assertThat(route.getAuthInfo().getUsername()).isEqualTo("admin");
    }

    // --- auth detection over the Traefik API, where names carry the @file suffix (#341) ---

    /** Serves the four Traefik API endpoints the adapter reads, with one router carrying {@code middlewares}. */
    private HttpServer startTraefikApiStub(String middlewaresJson) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/http/routers", exchange -> respond(exchange, """
            [{"name":"api-router@file","rule":"Host(`api.example.com`)","service":"api-service@file",
              "provider":"file","entryPoints":["websecure"],"middlewares":%s}]
            """.formatted(middlewaresJson)));
        server.createContext("/api/http/services", exchange -> respond(exchange, """
            [{"name":"api-service@file","loadBalancer":{"servers":[{"url":"http://10.0.0.9:7000"}]}}]
            """));
        server.createContext("/api/tcp/routers", exchange -> respond(exchange, "[]"));
        server.createContext("/api/tcp/services", exchange -> respond(exchange, "[]"));
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private ReverseProxyRoute apiRouteWithMiddlewares(String middlewaresJson) throws IOException {
        HttpServer server = startTraefikApiStub(middlewaresJson);
        try {
            TraefikReverseProxyAdapter apiAdapter = new TraefikReverseProxyAdapter(
                tempDir.resolve("remote-apps.yml").toString(),
                "http://localhost:" + server.getAddress().getPort(), "example.com");
            return apiAdapter.getReverseProxyRoutes().stream()
                .filter(r -> "api.example.com".equals(r.getDomainName()))
                .findFirst().orElseThrow();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void apiRoutes_bouncerNamedForwardAuth_doesNotMakeAPublicServiceReportAsAuthenticated() throws IOException {
        // "crowdsec-forwardauth@file" beat the old substring rule outright.
        assertThat(apiRouteWithMiddlewares("[\"crowdsec-forwardauth@file\",\"vaier-errors@file\"]").getAuthInfo())
            .isNull();
        assertThat(apiRouteWithMiddlewares("[\"crowdsec-bouncer@file\"]").getAuthInfo())
            .isNull();
    }

    @Test
    void apiRoutes_bouncerBeforeTheAuthChain_isSteppedOver() throws IOException {
        ReverseProxyRoute route = apiRouteWithMiddlewares(
            "[\"crowdsec-forwardauth@file\",\"oauth2-signin@file\",\"oauth2-authn@file\",\"vaier-authz@file\"]");

        assertThat(route.getAuthInfo()).isNotNull();
        assertThat(route.getAuthInfo().getUsername())
            .as("the label must name an authenticator, never the bouncer that happened to come first")
            .isEqualTo("oauth2-signin");
    }

    @Test
    void apiRoutes_bouncerAfterTheAuthChain_isIgnored() throws IOException {
        ReverseProxyRoute route = apiRouteWithMiddlewares(
            "[\"oauth2-signin@file\",\"oauth2-authn@file\",\"vaier-authz@file\",\"crowdsec-forwardauth@file\"]");

        assertThat(route.getAuthInfo()).isNotNull();
        assertThat(route.getAuthInfo().getUsername()).isEqualTo("oauth2-signin");
    }

    @Test
    void apiRoutes_todaysStackShape_isUnchanged() throws IOException {
        ReverseProxyRoute route = apiRouteWithMiddlewares(
            "[\"oauth2-signin@file\",\"oauth2-authn@file\",\"vaier-authz@file\",\"vaier-errors@file\"]");

        assertThat(route.getAuthInfo()).isNotNull();
        assertThat(route.getAuthInfo().getType()).isEqualTo("forwardAuth");
        assertThat(route.getAuthInfo().getUsername()).isEqualTo("oauth2-signin");
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
    void addLanReverseProxyRoute_withRequiresAuth_addsSocialMiddlewares() throws IOException {
        adapter.addLanReverseProxyRoute("nas.example.com", "192.168.3.50", 5000, "http", true, false, null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains(ServiceNames.VAIER_AUTHZ_MIDDLEWARE);
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

        adapter.ensureConsoleAuthMiddlewaresOnStartup();

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("X-Auth-Request-Name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void backfillSocialMiddlewares_addsRemoteNameToAnOlderVaierAuthzDefinition() throws IOException {
        // A config generated by an older Vaier: vaier-authz forwards only user/email/groups downstream.
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
                vaier-authz:
                  forwardAuth:
                    address: http://vaier:8080/authz/verify
                    trustForwardHeader: true
                    authResponseHeaders:
                    - Remote-User
                    - Remote-Email
                    - Remote-Groups
            """;
        Files.writeString(tempDir.resolve("remote-apps.yml"), preExisting);

        adapter.ensureConsoleAuthMiddlewaresOnStartup();

        var middlewares = (java.util.Map<String, Object>) http().get("middlewares");
        var authz = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) middlewares.get("vaier-authz")).get("forwardAuth");
        assertThat((List<String>) authz.get("authResponseHeaders"))
            .containsExactly("Remote-User", "Remote-Email", "Remote-Groups", "Remote-Name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensureConsoleAuthMiddlewares_freshInstall_createsSocialMiddlewaresAndOauth2ProxyService()
            throws IOException {
        // Fresh install: an empty remote-apps.yml, no published service, no http section at all.
        // The console's own compose-label routers (`vaier`, `vaier-identity`) always reference the
        // three social middlewares via @file, so they must be created on startup regardless.
        adapter.ensureConsoleAuthMiddlewaresOnStartup();

        var middlewares = (java.util.Map<String, Object>) http().get("middlewares");
        var services = (java.util.Map<String, Object>) http().get("services");

        // oauth2-signin: on 401, serve oauth2-proxy's sign-in page
        var signin = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) middlewares.get("oauth2-signin")).get("errors");
        assertThat((List<String>) signin.get("status")).containsExactly("401");
        assertThat(signin.get("service")).isEqualTo("oauth2-proxy-svc");
        assertThat(signin.get("query")).isEqualTo("/oauth2/sign_in?rd={url}");

        // oauth2-authn: Google forward-auth with the full X-Auth-Request-* header set
        var authn = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) middlewares.get("oauth2-authn")).get("forwardAuth");
        assertThat(authn.get("address")).isEqualTo("http://oauth2-proxy:4180/oauth2/auth");
        assertThat((List<String>) authn.get("authResponseHeaders"))
            .containsExactly("X-Auth-Request-Email", "X-Auth-Request-User", "X-Auth-Request-Name",
                    "X-Auth-Request-Connector", "X-Auth-Request-Connector-Uid");

        // vaier-authz: Vaier /authz/verify forward-auth with the Remote-* header set
        var authz = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) middlewares.get("vaier-authz")).get("forwardAuth");
        assertThat(authz.get("address")).isEqualTo("http://vaier:8080/authz/verify");
        assertThat((List<String>) authz.get("authResponseHeaders"))
            .containsExactly("Remote-User", "Remote-Email", "Remote-Groups", "Remote-Name");

        // oauth2-proxy-svc: the shared file-service the sign-in errors middleware points at
        var svc = (java.util.Map<String, Object>) services.get("oauth2-proxy-svc");
        var servers = (List<java.util.Map<String, Object>>)
            ((java.util.Map<String, Object>) svc.get("loadBalancer")).get("servers");
        assertThat(servers.get(0).get("url")).isEqualTo("http://oauth2-proxy:4180");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensureConsoleAuthMiddlewares_isIdempotent_leavesSingleDefinitionAndKeepsPublishedRoute()
            throws IOException {
        // A published public service already exists; the console middlewares are also already present.
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.ensureConsoleAuthMiddlewaresOnStartup();
        adapter.ensureConsoleAuthMiddlewaresOnStartup();

        var http = http();
        var routers = (java.util.Map<String, Object>) http.get("routers");
        var services = (java.util.Map<String, Object>) http.get("services");
        var middlewares = (java.util.Map<String, Object>) http.get("middlewares");

        // The three middlewares + oauth2-proxy-svc exist exactly once each
        assertThat(middlewares).containsKeys("oauth2-signin", "oauth2-authn", "vaier-authz");
        assertThat(services).containsKey("oauth2-proxy-svc");

        // The pre-existing published route and its backend are untouched
        assertThat(routers).containsKey("app-example-com-router");
        assertThat(services).containsKey("app-example-com-service");
        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("http://10.13.13.2:8080");
        // No duplicated definitions in the serialized YAML
        assertThat(content.split("vaier-authz:", -1).length - 1).isEqualTo(1);
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

    // --- Authelia decommission: Traefik-object cleanup (#305) ---

    @Test
    @SuppressWarnings("unchecked")
    void removeAutheliaTraefikObjects_removesLoginRouterAutheliaServiceAndAuthMiddleware_leavingSocialIntact()
            throws IOException {
        // A config that still carries the three Authelia objects alongside a live social route,
        // its per-host /oauth2/ helper router, the shared oauth2-proxy service and vaier-errors.
        String preExisting = """
            http:
              routers:
                authelia-router:
                  rule: "Host(`login.example.com`)"
                  entryPoints:
                  - websecure
                  service: authelia
                app-router:
                  rule: "Host(`app.example.com`)"
                  entryPoints:
                  - websecure
                  service: app-service
                  middlewares:
                  - oauth2-signin
                  - oauth2-authn
                  - vaier-authz
                  - vaier-errors
                app-example-com-oauth2-router:
                  rule: "Host(`app.example.com`) && PathPrefix(`/oauth2/`)"
                  entryPoints:
                  - websecure
                  service: oauth2-proxy-svc
              services:
                authelia:
                  loadBalancer:
                    servers:
                    - url: http://authelia:9091
                app-service:
                  loadBalancer:
                    servers:
                    - url: http://10.0.0.9:7000
                oauth2-proxy-svc:
                  loadBalancer:
                    servers:
                    - url: http://oauth2-proxy:4180
              middlewares:
                auth-middleware:
                  forwardAuth:
                    address: http://authelia:9091/api/verify
                oauth2-authn:
                  forwardAuth:
                    address: http://oauth2-proxy:4180/oauth2/auth
                vaier-errors:
                  errors:
                    status:
                    - "502"
                    service: vaier-error-pages
            """;
        Files.writeString(tempDir.resolve("remote-apps.yml"), preExisting);

        adapter.removeAutheliaTraefikObjects();

        var http = http();
        var routers = (java.util.Map<String, Object>) http.get("routers");
        var services = (java.util.Map<String, Object>) http.get("services");
        var middlewares = (java.util.Map<String, Object>) http.get("middlewares");

        // Exactly the three Authelia objects are gone
        assertThat(routers).doesNotContainKey("authelia-router");
        assertThat(services).doesNotContainKey("authelia");
        assertThat(middlewares).doesNotContainKey("auth-middleware");

        // Social + shared infra left intact
        assertThat(routers).containsKeys("app-router", "app-example-com-oauth2-router");
        assertThat(services).containsKeys("app-service", "oauth2-proxy-svc");
        assertThat(middlewares).containsKeys("oauth2-authn", "vaier-errors");
    }

    @Test
    void removeAutheliaTraefikObjects_isIdempotentWhenAlreadyAbsent() throws IOException {
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
                  - vaier-errors
              services:
                app-service:
                  loadBalancer:
                    servers:
                    - url: http://10.0.0.9:7000
            """;
        Files.writeString(tempDir.resolve("remote-apps.yml"), preExisting);

        adapter.removeAutheliaTraefikObjects();
        // second run must be a no-op and never throw
        adapter.removeAutheliaTraefikObjects();

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();
        assertThat(route.getDomainName()).isEqualTo("app.example.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeAutheliaTraefikObjects_removesConventionNamedLoginBackend_pointingAtAutheliaHost()
            throws IOException {
        // The login router's real backend follows the router-name convention
        // (login-<domain>-service), not the literal name "authelia" — so a name-only match misses
        // it and leaves it orphaned. It must be dropped by its Authelia backend URL instead.
        String preExisting = """
            http:
              services:
                login-example-com-service:
                  loadBalancer:
                    servers:
                    - url: http://authelia:9091
                oauth2-proxy-svc:
                  loadBalancer:
                    servers:
                    - url: http://oauth2-proxy:4180
                app-service:
                  loadBalancer:
                    servers:
                    - url: http://10.0.0.9:7000
            """;
        Files.writeString(tempDir.resolve("remote-apps.yml"), preExisting);

        adapter.removeAutheliaTraefikObjects();

        var services = (java.util.Map<String, Object>) http().get("services");
        assertThat(services).doesNotContainKey("login-example-com-service");
        assertThat(services).containsKeys("oauth2-proxy-svc", "app-service");
    }

    @Test
    void removeAutheliaTraefikObjects_emptyConfig_doesNotThrow() {
        adapter.removeAutheliaTraefikObjects();

        assertThat(adapter.getReverseProxyRoutes()).isEmpty();
    }

    // --- the root redirect that was a loop (rack.apalveien5, and four others on the live fleet) ---

    @Test
    void anEmptyRootRedirect_writesNoRedirectMiddleware() throws IOException {
        // The replacement was the bare host, which the regex `^https://host/?$` matches — so the browser was
        // redirected to the URL it had just been redirected from, until it gave up with TOO_MANY_REDIRECTS.
        adapter.addReverseProxyRoute("rack.example.com", "192.168.3.132", 8080, AuthMode.SOCIAL, "", null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).doesNotContain("redirectRegex");
        assertThat(content).doesNotContain("rack-example-com-redirect");
    }

    @Test
    void aRootRedirectToTheRootItself_writesNoRedirectMiddleware() throws IOException {
        adapter.addReverseProxyRoute("rack.example.com", "192.168.3.132", 8080, AuthMode.SOCIAL, "/", null);

        assertThat(Files.readString(tempDir.resolve("remote-apps.yml"))).doesNotContain("redirectRegex");
    }

    @Test
    void aRealRootRedirect_isStillWritten_andPointsSomewhereElse() throws IOException {
        adapter.addReverseProxyRoute("pihole.example.com", "192.168.3.9", 80, AuthMode.SOCIAL, "/admin", null);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("redirectRegex");
        assertThat(content).contains("replacement: https://pihole.example.com/admin");
    }

    @Test
    void deletingARoute_takesItsRedirectMiddlewareWithIt() throws IOException {
        // Three dead router-*-redirect middlewares outlived their routes on the live fleet, and every one of
        // them was a loop nobody could reach to clear: the API refuses a router that no longer exists.
        adapter.addReverseProxyRoute("pihole.example.com", "192.168.3.9", 80, AuthMode.SOCIAL, "/admin", null);
        assertThat(Files.readString(tempDir.resolve("remote-apps.yml"))).contains("pihole-example-com-redirect");

        adapter.deleteReverseProxyRouteByDnsName("pihole.example.com");

        assertThat(Files.readString(tempDir.resolve("remote-apps.yml")))
            .doesNotContain("pihole-example-com-redirect");
    }
    // --- streams: a non-HTTP port published as a Traefik tcp: router, matched by HostSNI on 443 ---
    //
    // Traefik terminates TLS with a per-hostname Let's Encrypt certificate (the same HTTP-01 resolver
    // the HTTP routers use — it issues for a TCP router's HostSNI name just the same) and forwards the
    // decrypted bytes to the backend port unchanged. The adapter only translates; every rule about what
    // a stream may carry is the domain's.

    @Test
    void addStreamRoute_writesATcpRouterMatchedByHostSni() throws IOException {
        adapter.addStreamRoute("mqtt.example.com", "172.20.0.1", 1883);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("tcp:");
        assertThat(content).contains("HostSNI(`mqtt.example.com`)");
        assertThat(content).contains(ServiceNames.ENTRY_POINT_WEBSECURE);
        assertThat(content).contains(ServiceNames.CERT_RESOLVER);
        assertThat(content).contains("address: 172.20.0.1:1883");
    }

    @Test
    void addStreamRoute_namesTheRouterAndServiceTheSameWayAnHttpRouteIs() throws IOException {
        adapter.addStreamRoute("mqtt.example.com", "172.20.0.1", 1883);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).contains("mqtt-example-com-router");
        assertThat(content).contains("mqtt-example-com-service");
    }

    @Test
    void addStreamRoute_carriesNoMiddlewares_becauseATcpRouterCannotHaveThem() throws IOException {
        adapter.addStreamRoute("mqtt.example.com", "172.20.0.1", 1883);

        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).doesNotContain("middlewares");
    }

    @Test
    void aStreamRoute_readsBackAsAStream() {
        adapter.addStreamRoute("mqtt.example.com", "172.20.0.1", 1883);

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();

        assertThat(routes).hasSize(1);
        ReverseProxyRoute route = routes.getFirst();
        assertThat(route.isStream()).isTrue();
        assertThat(route.getDomainName()).isEqualTo("mqtt.example.com");
        assertThat(route.getAddress()).isEqualTo("172.20.0.1");
        assertThat(route.getPort()).isEqualTo(1883);
    }

    @Test
    void anHttpRoute_readsBackAsNotAStream() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        assertThat(adapter.getReverseProxyRoutes().getFirst().isStream()).isFalse();
    }

    @Test
    void aStreamAndAnHttpRoute_coexistInTheOneFile() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.addStreamRoute("mqtt.example.com", "172.20.0.1", 1883);

        assertThat(adapter.getReverseProxyRoutes())
            .extracting(ReverseProxyRoute::getDomainName)
            .containsExactlyInAnyOrder("app.example.com", "mqtt.example.com");
    }

    @Test
    void deletingAStreamByDnsName_removesItsTcpRouterAndService() throws IOException {
        adapter.addStreamRoute("mqtt.example.com", "172.20.0.1", 1883);

        adapter.deleteReverseProxyRouteByDnsName("mqtt.example.com");

        assertThat(adapter.getReverseProxyRoutes()).isEmpty();
        String content = Files.readString(tempDir.resolve("remote-apps.yml"));
        assertThat(content).doesNotContain("mqtt-example-com-router");
        assertThat(content).doesNotContain("mqtt-example-com-service");
        assertThat(content).as("the last stream takes the tcp: section with it").doesNotContain("tcp:");
    }

    @Test
    void aStreamWearingASidecar_isStillAStream() {
        // Sidecars are re-applied on read by rebuilding the route. Rebuilding it positionally is how a
        // flag gets dropped without anything failing — the route would come back as an HTTP one, lose its
        // connect address and start being offered a login.
        adapter.addStreamRoute("mqtt.example.com", "172.20.0.1", 1883);
        adapter.setRouteDirectUrlDisabled("mqtt.example.com", null, true);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();

        assertThat(route.isDirectUrlDisabled()).isTrue();
        assertThat(route.isStream()).as("the sidecar must not cost it its kind").isTrue();
        assertThat(route.connectAddress()).isEqualTo("mqtt.example.com:443");
    }

    @Test
    void aStreamOnAMachinesLan_readsBackAsALanService() {
        adapter.addStreamRoute("mqtt.example.com", "192.168.3.50", 1883, true);

        ReverseProxyRoute route = adapter.getReverseProxyRoutes().getFirst();

        assertThat(route.isLanService()).isTrue();
        assertThat(route.isStream()).isTrue();
        assertThat(route.getProtocol()).isEqualTo(ReverseProxyRoute.STREAM_PROTOCOL);
    }

    @Test
    void unpublishingALanStream_takesItsLanMarkerWithIt() throws IOException {
        adapter.addStreamRoute("mqtt.example.com", "192.168.3.50", 1883, true);

        adapter.deleteReverseProxyRouteByDnsName("mqtt.example.com");

        assertThat(Files.readString(tempDir.resolve("remote-apps.yml")))
            .doesNotContain("x-vaier-lan-service");
    }

    @Test
    void deletingAStream_leavesTheHttpRoutesAlone() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        adapter.addStreamRoute("mqtt.example.com", "172.20.0.1", 1883);

        adapter.deleteReverseProxyRoute("mqtt-example-com-router");

        assertThat(adapter.getReverseProxyRoutes())
            .extracting(ReverseProxyRoute::getDomainName)
            .containsExactly("app.example.com");
    }

    @Test
    void deletingAnUnknownStream_stillSaysTheRouterIsNotThere() {
        adapter.addStreamRoute("mqtt.example.com", "172.20.0.1", 1883);

        assertThatThrownBy(() -> adapter.deleteReverseProxyRoute("nope-router"))
            .hasMessageContaining("Router not found");
    }

    // --- getReverseProxyConfig: the unflattened read the reverse proxy audit judges (#354) ---

    @Test
    void getReverseProxyConfig_isEmptyWhenTheFileIsNotThereYet() {
        ReverseProxyConfig config = adapter.getReverseProxyConfig();

        assertThat(config.routers()).isEmpty();
        assertThat(config.services()).isEmpty();
        assertThat(config.middlewares()).isEmpty();
    }

    @Test
    void getReverseProxyConfig_handsTheDomainEveryRouterServiceAndMiddleware() throws IOException {
        Files.writeString(tempDir.resolve("remote-apps.yml"), """
            http:
              routers:
                a-router:
                  rule: Host(`a.example.com`)
                  service: a-service
                  middlewares:
                    - a-redirect
                    - vaier-frame-guard@file
              services:
                a-service:
                  loadBalancer:
                    servers:
                      - url: http://10.13.13.2:8080
                vaier-error-pages:
                  loadBalancer:
                    servers:
                      - url: http://vaier:8080
              middlewares:
                a-redirect:
                  redirectRegex:
                    regex: "^https://a\\\\.example\\\\.com/?$"
                    replacement: "https://a.example.com/admin"
                vaier-errors:
                  errors:
                    status: ["502"]
                    service: vaier-error-pages
                    query: /error-pages/{status}
            tcp:
              routers:
                mqtt-router:
                  rule: HostSNI(`mqtt.example.com`)
                  service: mqtt-service
              services:
                mqtt-service:
                  loadBalancer:
                    servers:
                      - address: 172.20.0.1:1883
            """);

        ReverseProxyConfig config = adapter.getReverseProxyConfig();

        assertThat(config.routers()).extracting(ReverseProxyConfig.ConfiguredRouter::name)
            .containsExactlyInAnyOrder("a-router", "mqtt-router");
        ReverseProxyConfig.ConfiguredRouter httpRouter = config.routers().stream()
            .filter(r -> r.name().equals("a-router")).findFirst().orElseThrow();
        assertThat(httpRouter.protocol()).isEqualTo(ReverseProxyConfig.Protocol.HTTP);
        assertThat(httpRouter.serviceName()).isEqualTo("a-service");
        assertThat(httpRouter.middlewareNames()).containsExactly("a-redirect", "vaier-frame-guard@file");
        assertThat(config.routers().stream().filter(r -> r.name().equals("mqtt-router")).findFirst()
            .orElseThrow().protocol()).isEqualTo(ReverseProxyConfig.Protocol.TCP);

        assertThat(config.services()).extracting(ReverseProxyConfig.ConfiguredService::name)
            .containsExactlyInAnyOrder("a-service", "vaier-error-pages", "mqtt-service");

        ReverseProxyConfig.ConfiguredMiddleware redirect = config.middlewares().stream()
            .filter(m -> m.name().equals("a-redirect")).findFirst().orElseThrow();
        assertThat(redirect.redirectRegex()).isEqualTo("^https://a\\.example\\.com/?$");
        assertThat(redirect.redirectReplacement()).isEqualTo("https://a.example.com/admin");
        assertThat(config.middlewares().stream().filter(m -> m.name().equals("vaier-errors")).findFirst()
            .orElseThrow().errorsServiceName()).isEqualTo("vaier-error-pages");
    }

    @Test
    void getReverseProxyConfig_ofAFileVaierJustWroteIsClean() {
        // The end of the #354 loop: what the write path produces must survive the judgement it now faces.
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, AuthMode.SOCIAL, "/admin", null);
        adapter.addStreamRoute("mqtt.example.com", "172.20.0.1", 1883);

        assertThat(ReverseProxyAudit.of(adapter.getReverseProxyConfig()).findings()).isEmpty();
    }

    @Test
    void getReverseProxyConfig_ofAnUnpublishedRouteIsStillClean() {
        adapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, AuthMode.SOCIAL, "/admin", null);
        adapter.deleteReverseProxyRouteByDnsName("app.example.com");

        assertThat(ReverseProxyAudit.of(adapter.getReverseProxyConfig()).findings()).isEmpty();
    }
}
