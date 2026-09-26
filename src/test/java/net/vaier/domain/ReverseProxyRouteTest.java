package net.vaier.domain;

import net.vaier.config.ServiceNames;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.ReverseProxyRoute.RouteSetting;
import net.vaier.domain.Server.State;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForCallingServices;
import net.vaier.domain.port.ForProbingServiceSignIn;
import net.vaier.domain.port.ForProbingServiceVersion;
import net.vaier.domain.port.ForResolvingPeerIds;
import net.vaier.domain.port.ForResolvingServiceGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ReverseProxyRouteTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    private static ReverseProxyRoute routeWithMiddlewares(List<String> middlewares) {
        return new ReverseProxyRoute("app-router", "app.example.com", "10.0.0.1", 8080, "app-service",
            null, List.of("websecure"), null, middlewares);
    }

    @Test
    void authMode_isNone_whenNoAuthMiddlewareIsPresent() {
        assertThat(routeWithMiddlewares(List.of("vaier-errors")).authMode()).isEqualTo(AuthMode.NONE);
    }

    @Test
    void authMode_isNone_whenOnlyTheLegacyAutheliaMiddlewareIsPresent() {
        // Authelia is decommissioned: a leftover auth-middleware carries no social links, so the route
        // reads as NONE (the startup cleanup strips the middleware anyway).
        assertThat(routeWithMiddlewares(List.of("auth-middleware", "vaier-errors")).authMode())
            .isEqualTo(AuthMode.NONE);
    }

    @Test
    void authMode_isSocial_whenTheOauth2ChainIsPresent() {
        assertThat(routeWithMiddlewares(List.of("oauth2-signin", "oauth2-authn", "vaier-authz", "vaier-errors"))
            .authMode()).isEqualTo(AuthMode.SOCIAL);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void validateForPublication_rejectsBlankDnsName(String dnsName) {
        assertThatThrownBy(() -> ReverseProxyRoute.validateForPublication(dnsName, "10.0.0.1", 8080))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dnsName");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void validateForPublication_rejectsBlankAddress(String address) {
        assertThatThrownBy(() -> ReverseProxyRoute.validateForPublication("app.example.com", address, 8080))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void validateForPublication_rejectsOutOfRangePort(int port) {
        assertThatThrownBy(() -> ReverseProxyRoute.validateForPublication("app.example.com", "10.0.0.1", port))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 80, 443, 8080, 65535})
    void validateForPublication_acceptsValidInputs(int port) {
        assertThatCode(() -> ReverseProxyRoute.validateForPublication("app.example.com", "10.0.0.1", port))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void validateDnsName_rejectsBlank(String dnsName) {
        assertThatThrownBy(() -> ReverseProxyRoute.validateDnsName(dnsName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dnsName");
    }

    // --- pathPrefix ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "/", "  /  "})
    void normalisePathPrefix_blankOrRoot_returnsNull(String input) {
        assertThat(ReverseProxyRoute.normalisePathPrefix(input)).isNull();
    }

    @Test
    void normalisePathPrefix_preservesTrailingSlashAndGoodValues() {
        record Row(String description, String input, String expected) {}
        List<Row> rows = List.of(
            // The operator's trailing slash is part of their intent — backend SPAs sometimes serve
            // different content for /path vs /path/, so we keep the slash they typed instead of
            // silently dropping it (issue: bmp.native.corporater.dev/builder/ui).
            new Row("preserves operator trailing slash", "/auth/", "/auth/"),
            new Row("preserves operator trailing slash on a nested path", "/builder/ui/", "/builder/ui/"),
            new Row("preserves a good value unchanged", "/auth", "/auth"),
            new Row("preserves a good value unchanged with mixed case", "/CorpoWebserver", "/CorpoWebserver")
        );

        for (Row row : rows) {
            assertThat(ReverseProxyRoute.normalisePathPrefix(row.input())).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void normalisePathPrefix_trimsWhitespace() {
        assertThat(ReverseProxyRoute.normalisePathPrefix("  /auth  ")).isEqualTo("/auth");
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth", "auth/", "//double", "/foo bar", "/foo?bar", "/foo#bar", "/foo&bar"})
    void validatePathPrefix_rejectsBadShapes(String bad) {
        assertThatThrownBy(() -> ReverseProxyRoute.validatePathPrefix(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pathPrefix");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth", "/builder/ui", "/CorpoWebserver", "/a-b_c.d", "/x/y/z",
                            "/auth/", "/builder/ui/"})
    void validatePathPrefix_acceptsGoodShapes(String good) {
        assertThatCode(() -> ReverseProxyRoute.validatePathPrefix(good)).doesNotThrowAnyException();
    }

    @Test
    void validatePathPrefix_acceptsNull() {
        assertThatCode(() -> ReverseProxyRoute.validatePathPrefix(null)).doesNotThrowAnyException();
    }

    @Test
    void pathPrefix_retainedByGetter() {
        ReverseProxyRoute route = new ReverseProxyRoute("route", "bmp.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/auth");

        assertThat(route.getPathPrefix()).isEqualTo("/auth");
    }

    @Test
    void pathPrefix_defaultsToNullWhenUsingShorterConstructor() {
        ReverseProxyRoute route = new ReverseProxyRoute("route", "bmp.example.com", "10.0.0.1", 8080, "svc", null);

        assertThat(route.getPathPrefix()).isNull();
    }

    // --- isVaierManaged ---

    @Test
    void isVaierManaged_trueForPlainFileRouterName() {
        ReverseProxyRoute route = new ReverseProxyRoute("pump-router", "pump.example.com", "10.0.0.1", 80, "svc", null);

        assertThat(route.isVaierManaged()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"whoami@docker", "dashboard@internal", "app-router@file"})
    void isVaierManaged_falseForTraefikProviderSuffixedName(String apiRouterName) {
        ReverseProxyRoute route = new ReverseProxyRoute(apiRouterName, "x.example.com", "10.0.0.1", 80, "svc", null);

        assertThat(route.isVaierManaged()).isFalse();
    }

    @Test
    void isVaierManaged_falseForNullName() {
        ReverseProxyRoute route = new ReverseProxyRoute(null, "x.example.com", "10.0.0.1", 80, "svc", null);

        assertThat(route.isVaierManaged()).isFalse();
    }

    // --- hiddenFromLaunchpad ---

    @Test
    void hiddenFromLaunchpad_retainedByFullConstructor() {
        ReverseProxyRoute route = new ReverseProxyRoute("route", "api.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, null, true);

        assertThat(route.isHiddenFromLaunchpad()).isTrue();
    }

    @Test
    void hiddenFromLaunchpad_defaultsToFalseFromShorterConstructors() {
        ReverseProxyRoute hostOnly = new ReverseProxyRoute("route", "app.example.com", "10.0.0.1", 8080, "svc", null);
        ReverseProxyRoute pathRoute = new ReverseProxyRoute("route", "app.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/auth");
        ReverseProxyRoute lanRoute = ReverseProxyRoute.lanRoute("r", "nas.example.com", "192.168.3.50", 5000, "http", "svc");

        assertThat(hostOnly.isHiddenFromLaunchpad()).isFalse();
        assertThat(pathRoute.isHiddenFromLaunchpad()).isFalse();
        assertThat(lanRoute.isHiddenFromLaunchpad()).isFalse();
    }

    // --- launchpadAlias + launchpadDisplayName (domain rule for tile label) ---

    @Test
    void launchpadAlias_retainedByFullConstructor() {
        ReverseProxyRoute route = new ReverseProxyRoute("r", "app.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, null, false, "Grafana Prod");

        assertThat(route.getLaunchpadAlias()).isEqualTo("Grafana Prod");
    }

    @Test
    void launchpadAlias_defaultsToNullFromShorterConstructors() {
        ReverseProxyRoute hostOnly = new ReverseProxyRoute("r", "app.example.com", "10.0.0.1", 8080, "svc", null);

        assertThat(hostOnly.getLaunchpadAlias()).isNull();
    }

    @Test
    void launchpadDisplayName_hostOnlyOrPathBased_returnsTheRelevantLabel() {
        record Row(String description, ReverseProxyRoute route, String expected) {}
        List<Row> rows = List.of(
            new Row("host-only route returns the subdomain", route("grafana.example.com", "10.0.0.1", 8080), "grafana"),
            // grafana.myserver.example.com → "grafana"
            new Row("host-only route on a nested subdomain returns the first label",
                route("grafana.myserver.example.com", "10.13.13.2", 8080), "grafana"),
            new Row("path-based route returns the last path segment", pathRoute("svc.example.com", "/grafana"), "grafana"),
            new Row("path-based route with a nested path returns the final segment",
                pathRoute("svc.example.com", "/api/v1"), "v1")
        );

        for (Row row : rows) {
            assertThat(row.route().launchpadDisplayName("example.com")).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void launchpadDisplayName_aliasWins_overPathAndSubdomain() {
        ReverseProxyRoute route = new ReverseProxyRoute("r", "svc.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/grafana", false, "Grafana Prod");

        assertThat(route.launchpadDisplayName("example.com")).isEqualTo("Grafana Prod");
    }

    @Test
    void launchpadDisplayName_blankAliasIgnored() {
        ReverseProxyRoute route = new ReverseProxyRoute("r", "grafana.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, null, false, "   ");

        assertThat(route.launchpadDisplayName("example.com")).isEqualTo("grafana");
    }

    // --- launchpadDisplayNameFor: naming a host somebody reached ---

    @Test
    void launchpadDisplayNameFor_namesTheRouteServingThatHost() {
        List<ReverseProxyRoute> routes = List.of(
            route("plex.example.com", "10.0.0.1", 8080), route("grafana.example.com", "10.0.0.2", 3000));

        assertThat(ReverseProxyRoute.launchpadDisplayNameFor(routes, "grafana.example.com", "example.com"))
            .isEqualTo("grafana");
    }

    /** The label the operator picked, not the DNS label — the same name the launchpad tile shows. */
    @Test
    void launchpadDisplayNameFor_prefersTheOperatorsAlias() {
        List<ReverseProxyRoute> routes = List.of(new ReverseProxyRoute("r", "grafana.example.com",
            "10.0.0.1", 8080, "svc", null, null, null, null, null, false, false, null, null, false,
            "Grafana Prod"));

        assertThat(ReverseProxyRoute.launchpadDisplayNameFor(routes, "grafana.example.com", "example.com"))
            .isEqualTo("Grafana Prod");
    }

    /**
     * A host Vaier does not publish is still a host somebody reached — the console itself is one. No name
     * is a fair answer; a wrong one is not.
     */
    @Test
    void launchpadDisplayNameFor_isNullWhenNoRouteServesThatHost() {
        assertThat(ReverseProxyRoute.launchpadDisplayNameFor(
            List.of(route("plex.example.com", "10.0.0.1", 8080)), "vaier.example.com", "example.com"))
            .isNull();
        assertThat(ReverseProxyRoute.launchpadDisplayNameFor(null, "plex.example.com", "example.com"))
            .isNull();
        assertThat(ReverseProxyRoute.launchpadDisplayNameFor(List.of(), null, "example.com")).isNull();
    }

    /** Several routes share one host when they are path-scoped; the host-only one is what a host names. */
    @Test
    void launchpadDisplayNameFor_prefersTheHostOnlyRouteOverAPathScopedSibling() {
        List<ReverseProxyRoute> routes = List.of(
            pathRoute("svc.example.com", "/grafana"), route("svc.example.com", "10.0.0.1", 8080));

        assertThat(ReverseProxyRoute.launchpadDisplayNameFor(routes, "svc.example.com", "example.com"))
            .isEqualTo("svc");
    }

    @Test
    void launchpadDisplayNameFor_matchesAHostWhateverItsCase() {
        assertThat(ReverseProxyRoute.launchpadDisplayNameFor(
            List.of(route("grafana.example.com", "10.0.0.1", 8080)), "Grafana.Example.com", "example.com"))
            .isEqualTo("grafana");
    }

    // --- launchpadIconQuery (domain owns the icon lookup identity) ---

    @Test
    void launchpadIconQuery_hostOnly_emitsHostParamOnly() {
        ReverseProxyRoute route = route("grafana.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadIconQuery()).isEqualTo("host=grafana.example.com");
    }

    @Test
    void launchpadIconQuery_pathBased_includesPathPrefix() {
        ReverseProxyRoute route = pathRoute("services.example.com", "/grafana");

        assertThat(route.launchpadIconQuery())
            .isEqualTo("host=services.example.com&pathPrefix=%2Fgrafana");
    }

    @Test
    void launchpadIconQuery_pathBasedSiblings_differByPathPrefix() {
        // The whole point: siblings on one FQDN must produce distinct queries so the
        // launchpad doesn't fight over a single cache entry.
        ReverseProxyRoute grafana = pathRoute("services.example.com", "/grafana");
        ReverseProxyRoute jenkins = pathRoute("services.example.com", "/jenkins");

        assertThat(grafana.launchpadIconQuery())
            .isNotEqualTo(jenkins.launchpadIconQuery());
    }

    // --- launchpadVisibility (domain rule consolidating every reason a route is shown/hidden) ---

    @Test
    void launchpadVisibility_hostOk_visibleActive() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadVisibility(State.OK))
            .isEqualTo(LaunchpadVisibility.VISIBLE_ACTIVE);
    }

    @Test
    void launchpadVisibility_hostUnreachable_visibleInactive() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadVisibility(State.UNREACHABLE))
            .isEqualTo(LaunchpadVisibility.VISIBLE_INACTIVE);
    }

    @Test
    void launchpadVisibility_hiddenFromLaunchpad_notVisibleEvenWhenHealthy() {
        ReverseProxyRoute hidden = new ReverseProxyRoute("r", "app.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, null, true);

        assertThat(hidden.launchpadVisibility(State.OK))
            .isEqualTo(LaunchpadVisibility.NOT_VISIBLE);
    }

    // --- viewer-adaptive gating (public, viewer-adaptive launchpad) ---

    private static final ForResolvingServiceGroup NO_RULES = host -> List.of();

    private ReverseProxyRoute socialRoute(String host) {
        return new ReverseProxyRoute("r", host, "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null,
            List.of(ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE, ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
                ServiceNames.VAIER_AUTHZ_MIDDLEWARE), null, false);
    }

    private AccessEntry admin() {
        return AccessEntry.builder().email("admin@example.com").role(Role.ADMIN).groups(List.of()).build();
    }

    private AccessEntry user(String... groups) {
        return AccessEntry.builder().email("u@example.com").role(Role.USER).groups(List.of(groups)).build();
    }

    private AccessEntry pending() {
        return AccessEntry.builder().email("p@example.com").role(Role.PENDING).groups(List.of()).build();
    }

    @Test
    void launchpadVisibility_socialRouteAndAnonymousViewer_notVisible() {
        assertThat(socialRoute("internal.example.com")
            .launchpadVisibility(State.OK, null, NO_RULES))
            .isEqualTo(LaunchpadVisibility.NOT_VISIBLE);
    }

    @Test
    void launchpadVisibility_socialRouteAndAdminViewer_followsHealthRules() {
        ReverseProxyRoute route = socialRoute("internal.example.com");

        assertThat(route.launchpadVisibility(State.OK, admin(), NO_RULES))
            .isEqualTo(LaunchpadVisibility.VISIBLE_ACTIVE);
        assertThat(route.launchpadVisibility(State.UNREACHABLE, admin(), NO_RULES))
            .isEqualTo(LaunchpadVisibility.VISIBLE_INACTIVE);
    }

    @Test
    void launchpadVisibility_socialRouteAndUserInAllowedGroup_visible() {
        ForResolvingServiceGroup rules = host -> List.of("devs");

        assertThat(socialRoute("git.example.com")
            .launchpadVisibility(State.OK, user("devs"), rules))
            .isEqualTo(LaunchpadVisibility.VISIBLE_ACTIVE);
    }

    @Test
    void launchpadVisibility_socialRouteAndUserNotInAllowedGroup_notVisible() {
        ForResolvingServiceGroup rules = host -> List.of("devs");

        assertThat(socialRoute("git.example.com")
            .launchpadVisibility(State.OK, user("family"), rules))
            .isEqualTo(LaunchpadVisibility.NOT_VISIBLE);
    }

    @Test
    void launchpadVisibility_socialRouteAndPendingViewer_notVisible() {
        assertThat(socialRoute("git.example.com")
            .launchpadVisibility(State.OK, pending(), NO_RULES))
            .isEqualTo(LaunchpadVisibility.NOT_VISIBLE);
    }

    @Test
    void launchpadVisibility_publicRoute_visibleToAnonymousViewer() {
        ReverseProxyRoute route = route("public.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadVisibility(State.OK, null, NO_RULES))
            .isEqualTo(LaunchpadVisibility.VISIBLE_ACTIVE);
    }

    @Test
    void launchpadVisibility_hiddenWins_overAViewerWhoCouldOtherwiseSeeIt() {
        ReverseProxyRoute hidden = new ReverseProxyRoute("r", "app.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, null, true);

        assertThat(hidden.launchpadVisibility(State.OK, admin(), NO_RULES))
            .isEqualTo(LaunchpadVisibility.NOT_VISIBLE);
    }

    // --- domain rules over existing-routes lists ---

    @Test
    void conflictsWithExisting_sameDomainAndPath_true() {
        ReverseProxyRoute existing = pathRoute("bmp.example.com", "/auth");
        assertThat(ReverseProxyRoute.conflictsWithExisting(List.of(existing), "bmp.example.com", "/auth")).isTrue();
    }

    @Test
    void conflictsWithExisting_sameDomainDifferentPath_false() {
        ReverseProxyRoute existing = pathRoute("bmp.example.com", "/auth");
        assertThat(ReverseProxyRoute.conflictsWithExisting(List.of(existing), "bmp.example.com", "/CorpoWebserver")).isFalse();
    }

    @Test
    void conflictsWithExisting_bothNullPaths_true() {
        ReverseProxyRoute existing = route("bmp.example.com", "10.0.0.1", 8080);
        assertThat(ReverseProxyRoute.conflictsWithExisting(List.of(existing), "bmp.example.com", null)).isTrue();
    }

    @Test
    void findByFqdnAndPath_found_returnsRoute() {
        ReverseProxyRoute target = pathRoute("bmp.example.com", "/auth");
        ReverseProxyRoute other = pathRoute("bmp.example.com", "/CorpoWebserver");
        assertThat(ReverseProxyRoute.findByFqdnAndPath(List.of(target, other), "bmp.example.com", "/auth"))
            .contains(target);
    }

    @Test
    void findByFqdnAndPath_notFound_returnsEmpty() {
        assertThat(ReverseProxyRoute.findByFqdnAndPath(List.of(), "bmp.example.com", "/auth")).isEmpty();
    }

    private static ReverseProxyRoute pathRoute(String domain, String path) {
        return new ReverseProxyRoute("route", domain, "10.0.0.1", 8080, "svc", null,
            null, null, null, null, false, false, null, path);
    }

    // --- launchpadUrl / protocol (#231) ---

    @Test
    void launchpadUrl_publicRoute_isTheDirectHttpsUrl() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
            .isEqualTo("https://app.example.com");
    }

    @Test
    void launchpadUrl_socialRoute_linksDirectlyRelyingOnDomainWideSso() {
        // A social-gated route links straight to its host — no Authelia portal, no /oauth2/start
        // bounce. The domain-wide oauth2-proxy cookie means an already-signed-in user passes edge
        // auth; an anonymous user is met at the edge with the Google sign-in.
        ReverseProxyRoute route = new ReverseProxyRoute("r", "internal.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null,
            List.of(ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE, ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
                ServiceNames.VAIER_AUTHZ_MIDDLEWARE), null, false);

        assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
            .isEqualTo("https://internal.example.com");
    }

    @Test
    void launchpadUrl_pathPrefixEmittedVerbatimWithOrWithoutTrailingSlash() {
        record Row(String description, String pathPrefix, String expected) {}
        List<Row> rows = List.of(
            // Operator typed `/builder/ui/` — we don't strip the slash, and we don't auto-add one
            // either; the launchpad URL uses the pathPrefix as the landing path as-is.
            new Row("path prefix with trailing slash is emitted verbatim", "/builder/ui/", "https://bmp.example.com/builder/ui/"),
            // Operator typed `/builder/ui` (no slash) — emitted as-is. If the backend needs a slash,
            // the operator can express that via a redirect.
            new Row("path prefix without trailing slash is emitted verbatim", "/builder/ui", "https://bmp.example.com/builder/ui")
        );

        for (Row row : rows) {
            ReverseProxyRoute route = new ReverseProxyRoute("r", "bmp.example.com", "10.0.0.1", 8080, "svc",
                null, null, null, null, null, false, false, null, row.pathPrefix());

            assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
                .as(row.description())
                .isEqualTo(row.expected());
        }
    }

    @Test
    void launchpadUrl_redirectWinsOverPathPrefix() {
        // pathPrefix is the Traefik matcher; when a redirect is registered, the redirect is the
        // operator's intended landing path and supersedes the pathPrefix in the launchpad URL.
        ReverseProxyRoute route = new ReverseProxyRoute("r", "bmp.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, "/builder/ui/", false, false, null, "/builder/ui");

        assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
            .isEqualTo("https://bmp.example.com/builder/ui/");
    }

    @Test
    void launchpadUrl_socialRouteWithRedirect_linksDirectlyToTheLandingPath() {
        ReverseProxyRoute route = new ReverseProxyRoute("r", "bmp.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null,
            List.of(ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE, ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
                ServiceNames.VAIER_AUTHZ_MIDDLEWARE),
            "/builder/ui/", false, false, null, "/builder/ui");

        assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
            .isEqualTo("https://bmp.example.com/builder/ui/");
    }

    @Test
    void launchpadUrl_socialRouteWithPathPrefix_linksDirectlyToThePathPrefix() {
        ReverseProxyRoute route = new ReverseProxyRoute("r", "bmp.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null,
            List.of(ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE, ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
                ServiceNames.VAIER_AUTHZ_MIDDLEWARE),
            null, false, false, null, "/builder/ui/");

        assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
            .isEqualTo("https://bmp.example.com/builder/ui/");
    }

    @Test
    void normaliseProtocol_defaultsBlankToHttpAndLowercases() {
        assertThat(ReverseProxyRoute.normaliseProtocol(null)).isEqualTo("http");
        assertThat(ReverseProxyRoute.normaliseProtocol("   ")).isEqualTo("http");
        assertThat(ReverseProxyRoute.normaliseProtocol("HTTPS")).isEqualTo("https");
    }

    @Test
    void validateProtocol_rejectsAnythingButHttpAndHttps() {
        assertThatCode(() -> ReverseProxyRoute.validateProtocol("http")).doesNotThrowAnyException();
        assertThatCode(() -> ReverseProxyRoute.validateProtocol("https")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ReverseProxyRoute.validateProtocol("ftp"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("protocol");
    }

    @Test
    void hasRouteFor_trueWhenAnExistingRouteSharesAddressAndPort() {
        List<ReverseProxyRoute> existing = List.of(
            route("a.example.com", "grafana", 3000),
            route("b.example.com", "172.20.0.1", 8080));

        assertThat(ReverseProxyRoute.hasRouteFor(existing, "grafana", 3000)).isTrue();
        assertThat(ReverseProxyRoute.hasRouteFor(existing, "172.20.0.1", 8080)).isTrue();
    }

    @Test
    void hasRouteFor_falseWhenAddressOrPortDiffers() {
        List<ReverseProxyRoute> existing = List.of(route("a.example.com", "grafana", 3000));

        assertThat(ReverseProxyRoute.hasRouteFor(existing, "grafana", 9999)).isFalse();
        assertThat(ReverseProxyRoute.hasRouteFor(existing, "prometheus", 3000)).isFalse();
        assertThat(ReverseProxyRoute.hasRouteFor(List.of(), "grafana", 3000)).isFalse();
    }

    // --- hostState ---

    @Test
    void hostState_runningLocalServiceOnPort_returnsOk() {
        ReverseProxyRoute route = route("app.example.com", "my-container", 8080);
        List<DockerService> local = List.of(runningLocal("my-container", 8080));

        assertThat(route.hostState(local, List.of())).isEqualTo(State.OK);
    }

    @Test
    void hostState_stoppedLocalServiceOnPort_returnsUnreachable() {
        ReverseProxyRoute route = route("app.example.com", "my-container", 8080);
        DockerService stopped = new DockerService("id", "my-container", "image", "v",
            List.of(new PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "exited");

        assertThat(route.hostState(List.of(stopped), List.of())).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void hostState_connectedVpnPeerReturnsOk() {
        record Row(String description, String host, String address, String allowedIps) {}
        List<Row> rows = List.of(
            new Row("connected vpn peer matching address returns OK", "app.example.com", "10.13.13.2", "10.13.13.2/32"),
            new Row("peer address with connected peer returns OK regardless of local containers",
                "openhab.relay.example.com", "10.13.13.3", "10.13.13.3/32,192.168.1.0/24")
        );

        for (Row row : rows) {
            ReverseProxyRoute route = route(row.host(), row.address(), 8080);
            VpnClient connected = connectedPeer(row.allowedIps());

            assertThat(route.hostState(List.of(), List.of(connected))).as(row.description()).isEqualTo(State.OK);
        }
    }

    @Test
    void hostState_staleVpnPeer_returnsUnreachable() {
        ReverseProxyRoute route = route("app.example.com", "10.13.13.2", 8080);
        VpnClient stale = new VpnClient("pk", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(route.hostState(List.of(), List.of(stale))).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void hostState_peerAddress_aLocalContainerOnTheSamePort_cannotVouchForAStalePeer() {
        // The live false positive: openhab on a relay peer at :8080 read OK for forty minutes with the
        // peer's tunnel down, because Vaier's own container listens on 8080. The address is the peer's,
        // so only the peer's tunnel gets a say.
        ReverseProxyRoute route = route("openhab.relay.example.com", "10.13.13.3", 8080);
        VpnClient stale = new VpnClient("pk", "10.13.13.3/32,192.168.1.0/24", "1.2.3.4", "51820", "0", "0", "0");
        List<DockerService> local = List.of(runningLocal("vaier", 8080));

        assertThat(route.hostState(local, List.of(stale))).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void hostState_noMatchingLocalOrPeer_returnsUnreachable() {
        ReverseProxyRoute route = route("app.example.com", "192.168.99.1", 8080);

        assertThat(route.hostState(List.of(), List.of())).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void hostState_lanServiceWithRelay_relayConnected_reachabilityOk_returnsOk() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "nas.example.com", "192.168.3.50", 5000, "http", "svc");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        VpnClient connectedRelay = connectedPeer("10.13.13.5/32");

        assertThat(route.hostState(List.of(), List.of(connectedRelay), List.of(relay), null,
            Map.of("192.168.3.50", Reachability.OK))).isEqualTo(State.OK);
    }

    @Test
    void hostState_lanServiceWithRelay_relayDisconnected_returnsUnreachable() {
        // Even when we have no LAN-reachability signal, a dead relay tunnel means the service
        // is unreachable to anyone going through Vaier — UNREACHABLE wins over UNKNOWN.
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "nas.example.com", "192.168.3.50", 5000, "http", "svc");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");

        assertThat(route.hostState(List.of(), List.of(), List.of(relay), null, null)).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void hostState_lanServiceInsideServerLanCidr_reachabilityOk_returnsOk() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "box.example.com", "172.31.5.20", 8080, "http", "svc");

        assertThat(route.hostState(List.of(), List.of(), List.of(), "172.31.0.0/16",
            Map.of("172.31.5.20", Reachability.OK))).isEqualTo(State.OK);
    }

    @Test
    void hostState_lanServiceNeitherRelayNorServerLanCidr_returnsUnreachable() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "box.example.com", "10.99.99.99", 8080, "http", "svc");

        assertThat(route.hostState(List.of(), List.of(), List.of(), "172.31.0.0/16")).isEqualTo(State.UNREACHABLE);
    }

    // --- hostState with LAN-host reachability (issue #208) ---

    @Test
    void hostState_lanService_relayConnected_lanHostDown_returnsUnreachable() {
        // Issue #208: even with the relay tunnel up, a LAN service whose host machine is known
        // unreachable (reachability probe returned DOWN) must report UNREACHABLE so the
        // launchpad and services UIs can show the host as offline.
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "nas.example.com", "192.168.3.50", 5000, "http", "svc");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        VpnClient connectedRelay = connectedPeer("10.13.13.5/32");

        assertThat(route.hostState(List.of(), List.of(connectedRelay), List.of(relay), null,
            Map.of("192.168.3.50", Reachability.DOWN))).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void hostState_lanService_lanHostUnknown_returnsUnknown() {
        // A never-probed LAN host (or one whose probe hasn't landed yet) must NOT collapse to
        // OK — that would render the icon green when we don't actually have a signal. Return
        // UNKNOWN so the UI can show grey. Issue #208.
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "nas.example.com", "192.168.3.50", 5000, "http", "svc");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        VpnClient connectedRelay = connectedPeer("10.13.13.5/32");

        assertThat(route.hostState(List.of(), List.of(connectedRelay), List.of(relay), null, Map.of()))
            .isEqualTo(State.UNKNOWN);
        assertThat(route.hostState(List.of(), List.of(connectedRelay), List.of(relay), null,
            Map.of("192.168.3.50", Reachability.UNKNOWN))).isEqualTo(State.UNKNOWN);
    }

    @Test
    void hostState_lanService_insideServerLanCidr_lanHostDown_returnsUnreachable() {
        // A LAN host inside the Vaier server's own subnet is route-reachable from the server,
        // but the machine itself can still be powered off — the reachability probe is the
        // authoritative signal.
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "box.example.com", "172.31.5.20", 8080, "http", "svc");

        assertThat(route.hostState(List.of(), List.of(), List.of(), "172.31.0.0/16",
            Map.of("172.31.5.20", Reachability.DOWN))).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void hostState_lanService_nullReachabilities_returnsUnknown() {
        // No data at all means we have no signal — treat as UNKNOWN, same as an empty map.
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "nas.example.com", "192.168.3.50", 5000, "http", "svc");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        VpnClient connectedRelay = connectedPeer("10.13.13.5/32");

        assertThat(route.hostState(List.of(), List.of(connectedRelay), List.of(relay), null, null))
            .isEqualTo(State.UNKNOWN);
    }

    @Test
    void hostState_peerRoute_reachabilityMapIgnored() {
        // The LAN reachability signal applies only to LAN services — passing the map on a peer
        // route must not change the outcome.
        ReverseProxyRoute route = route("app.example.com", "10.13.13.2", 8080);
        VpnClient connected = connectedPeer("10.13.13.2/32");

        assertThat(route.hostState(List.of(), List.of(connected), List.of(), null,
            Map.of("10.13.13.2", Reachability.DOWN))).isEqualTo(State.OK);
    }

    // --- launchpadVisibility with UNKNOWN host state (issue #208) ---

    @Test
    void launchpadVisibility_hostStateUnknown_returnsVisibleActive() {
        // We don't know the host is down, so don't dim or pin a red dot on the tile.
        ReverseProxyRoute route = route("app.example.com", "10.13.13.2", 8080);

        assertThat(route.launchpadVisibility(State.UNKNOWN))
            .isEqualTo(LaunchpadVisibility.VISIBLE_ACTIVE);
    }

    // --- launchpadLiveness (dot-presentation tri-state derived from host state, issue #208) ---

    @Test
    void launchpadLiveness_hostStateOk_isLive() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadLiveness(State.OK)).isEqualTo(LaunchpadLiveness.LIVE);
    }

    @Test
    void launchpadLiveness_hostStateUnreachable_isOffline() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadLiveness(State.UNREACHABLE)).isEqualTo(LaunchpadLiveness.OFFLINE);
    }

    @Test
    void launchpadLiveness_hostStateUnknown_isPending() {
        // A host we haven't probed yet must read grey (PENDING), not green — the whole point
        // of the separate liveness tri-state (LaunchpadVisibility keeps UNKNOWN as ACTIVE).
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadLiveness(State.UNKNOWN)).isEqualTo(LaunchpadLiveness.PENDING);
    }

    // --- backingContainer (issue #210) ---

    @Test
    void backingContainer_peerRoute_matchesContainerByVpnIpAndPublicPort() {
        ReverseProxyRoute route = route("app.example.com", "10.13.13.6", 6875);
        DockerService bookstack = new DockerService("id", "bookstack",
            "linuxserver/bookstack:24.05", "24.05",
            List.of(new PortMapping(80, 6875, "tcp", "0.0.0.0")), List.of(), "running");

        assertThat(route.backingContainer(List.of(),
            Map.of("10.13.13.6", List.of(bookstack)), Map.of()))
            .contains(bookstack);
    }

    @Test
    void backingContainer_vaierServerRoute_matchesContainerByName() {
        ReverseProxyRoute route = route("app.example.com", "grafana", 3000);
        DockerService grafana = new DockerService("id", "grafana",
            "grafana/grafana:11.3.0", "11.3.0",
            List.of(new PortMapping(3000, 3000, "tcp", "0.0.0.0")), List.of(), "running");

        assertThat(route.backingContainer(List.of(grafana), Map.of(), Map.of()))
            .contains(grafana);
    }

    @Test
    void backingContainer_vaierServerRoute_matchesContainerByPortWhenAddressIsNotAName() {
        // Containers off the Vaier network are published on the docker gateway IP + public port.
        ReverseProxyRoute route = route("app.example.com", "172.20.0.1", 9000);
        DockerService app = new DockerService("id", "some-app", "ghcr.io/acme/app:2.1", "2.1",
            List.of(new PortMapping(8080, 9000, "tcp", "0.0.0.0")), List.of(), "running");

        assertThat(route.backingContainer(List.of(app), Map.of(), Map.of()))
            .contains(app);
    }

    @Test
    void backingContainer_lanServiceRoute_matchesLanServerContainerByAddress() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "r", "photos.example.com", "192.168.3.50", 2342, "http", "svc");
        DockerService photoprism = new DockerService("id", "photoprism",
            "photoprism/photoprism:240915", "240915",
            List.of(new PortMapping(2342, 2342, "tcp", "0.0.0.0")), List.of(), "running");

        assertThat(route.backingContainer(List.of(),
            Map.of(), Map.of("192.168.3.50", List.of(photoprism))))
            .contains(photoprism);
    }

    @Test
    void backingContainer_lanServicePublishedAsBareHostPort_returnsEmpty() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "r", "printer.example.com", "192.168.3.99", 631, "http", "svc");

        assertThat(route.backingContainer(List.of(), Map.of(), Map.of())).isEmpty();
    }

    @Test
    void backingContainer_peerRouteWithNoMatchingContainer_doesNotFallBackToVaierServer() {
        // The peer is known (reachable) but its container is gone — must not mis-attribute a
        // Vaier-server container that happens to listen on the same port.
        ReverseProxyRoute route = route("app.example.com", "10.13.13.6", 8080);
        DockerService unrelated = new DockerService("id", "unrelated", "nginx:1.27", "1.27",
            List.of(new PortMapping(8080, 8080, "tcp", "0.0.0.0")), List.of(), "running");

        assertThat(route.backingContainer(List.of(unrelated),
            Map.of("10.13.13.6", List.of()), Map.of()))
            .isEmpty();
    }

    @Test
    void backingContainer_lanNativeService_doesNotMatchContainerByPrivatePort() {
        // A service running natively on a machine that is also a registered LAN server. The
        // route's port is the host port the native process binds; a container on the same
        // machine happens to listen on that port internally but is published elsewhere. The
        // container's host port can never collide with the native process's, so matching must
        // be on the published (host) port only — the native service has no backing container.
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "r", "app.example.com", "192.168.3.50", 8080, "http", "svc");
        DockerService unrelated = new DockerService("id", "unrelated", "nginx:1.27", "1.27",
            List.of(new PortMapping(8080, 32768, "tcp", "0.0.0.0")), List.of(), "running");

        assertThat(route.backingContainer(List.of(),
            Map.of(), Map.of("192.168.3.50", List.of(unrelated))))
            .isEmpty();
    }

    @Test
    void backingContainer_peerRoute_doesNotMatchContainerByPrivatePort() {
        // Same rule for peer containers: the route stores the published port, so a container
        // that merely listens on that port internally (but is published elsewhere) is not it.
        ReverseProxyRoute route = route("app.example.com", "10.13.13.6", 8080);
        DockerService unrelated = new DockerService("id", "unrelated", "nginx:1.27", "1.27",
            List.of(new PortMapping(8080, 32768, "tcp", "0.0.0.0")), List.of(), "running");

        assertThat(route.backingContainer(List.of(),
            Map.of("10.13.13.6", List.of(unrelated)), Map.of()))
            .isEmpty();
    }

    // --- version endpoint (issue #210 — LAN-native version) ---

    @Test
    void hasVersionEndpoint_trueOnlyWhenBothEndpointAndPropertySet() {
        assertThat(versionRoute("/sys/metrics", "display").hasVersionEndpoint()).isTrue();
        assertThat(versionRoute(null, null).hasVersionEndpoint()).isFalse();
        assertThat(versionRoute("/sys/metrics", null).hasVersionEndpoint()).isFalse();
        assertThat(versionRoute("/sys/metrics", "  ").hasVersionEndpoint()).isFalse();
        assertThat(versionRoute("", "display").hasVersionEndpoint()).isFalse();
    }

    @Test
    void versionProbeUrl_buildsUrlFromServiceAddressAndRelativeEndpoint() {
        ReverseProxyRoute route = versionRoute("sys/metrics?name[]=system_info", "display");
        assertThat(route.versionProbeUrl())
            .isEqualTo("http://192.168.3.50:9000/sys/metrics?name[]=system_info");
    }

    @Test
    void versionProbeUrl_keepsLeadingSlashEndpointAsSinglePath() {
        ReverseProxyRoute route = versionRoute("/status", "display");
        assertThat(route.versionProbeUrl()).isEqualTo("http://192.168.3.50:9000/status");
    }

    @Test
    void versionProbeUrl_usesAbsoluteEndpointVerbatim() {
        ReverseProxyRoute route = versionRoute("https://other.host:8443/v", "display");
        assertThat(route.versionProbeUrl()).isEqualTo("https://other.host:8443/v");
    }

    @Test
    void versionProbeUrl_isNullWhenNoEndpointConfigured() {
        assertThat(versionRoute(null, null).versionProbeUrl()).isNull();
    }

    @Test
    void versionProbeUrl_isPathPrefixIndependent() {
        // The version endpoint is the operator's full backend path; pathPrefix is a Traefik
        // matcher and stays out of the probe URL. The operator types the path they want.
        ReverseProxyRoute route = pathVersionRoute("/builder", "/builder/version", "version");
        assertThat(route.versionProbeUrl()).isEqualTo("http://192.168.3.50:9000/builder/version");
    }

    // --- originUrl (icon resolution reaches the backend, not the gated public host) ---

    @Test
    void originUrl_buildsSchemeHostPortFromTheBackingService() {
        assertThat(lanRoute("rack.example.com", "192.168.3.132", 8080).originUrl())
            .isEqualTo("http://192.168.3.132:8080");
    }

    @Test
    void originUrl_defaultsToHttpWhenNoProtocolIsRecorded() {
        assertThat(route("app.example.com", "vaier", 8080).originUrl())
            .isEqualTo("http://vaier:8080");
    }

    @Test
    void originUrlFor_findsTheRouteServingThatHost() {
        List<ReverseProxyRoute> routes = List.of(
            lanRoute("other.example.com", "10.0.0.1", 80),
            lanRoute("rack.example.com", "192.168.3.132", 8080));

        assertThat(ReverseProxyRoute.originUrlFor(routes, "rack.example.com", null))
            .contains("http://192.168.3.132:8080");
    }

    @Test
    void originUrlFor_treatsAnEmptyPathPrefixAsNone() {
        // The icon lookup normalises a missing prefix to ""; the route stores null. Same route.
        List<ReverseProxyRoute> routes = List.of(lanRoute("rack.example.com", "192.168.3.132", 8080));

        assertThat(ReverseProxyRoute.originUrlFor(routes, "rack.example.com", ""))
            .contains("http://192.168.3.132:8080");
    }

    @Test
    void originUrlFor_distinguishesPathRoutesSharingAHost() {
        List<ReverseProxyRoute> routes = List.of(pathRoute("shared.example.com", "/builder"));

        assertThat(ReverseProxyRoute.originUrlFor(routes, "shared.example.com", "/builder"))
            .contains("http://10.0.0.1:8080");
        assertThat(ReverseProxyRoute.originUrlFor(routes, "shared.example.com", null)).isEmpty();
    }

    @Test
    void originUrlFor_isEmptyWhenNoRouteServesThatHost() {
        assertThat(ReverseProxyRoute.originUrlFor(List.of(), "rack.example.com", null)).isEmpty();
    }

    @Test
    void probeVersion_delegatesToProberWithBuiltUrlAndProperty() {
        ReverseProxyRoute route = versionRoute("sys/metrics?name[]=system_info", "display");
        ForProbingServiceVersion prober = (url, property) ->
            "http://192.168.3.50:9000/sys/metrics?name[]=system_info".equals(url) && "display".equals(property)
                ? Optional.of("5.0.0.0") : Optional.empty();

        assertThat(route.probeVersion(prober)).contains("5.0.0.0");
    }

    @Test
    void probeVersion_emptyAndDoesNotCallProber_whenNoEndpointConfigured() {
        ReverseProxyRoute route = versionRoute(null, null);
        ForProbingServiceVersion prober = (url, property) -> {
            throw new AssertionError("prober must not be invoked when no version endpoint is configured");
        };

        assertThat(route.probeVersion(prober)).isEmpty();
    }

    @Test
    void detectOwnSignIn_asksTheBackendItselfWhereTheRouteLands_followingOneSameOriginRedirect() {
        record Row(String pathPrefix, String rootRedirect, String firstUrl) {}
        for (Row row : List.of(
                new Row(null, null, "http://192.168.3.50:9000/"),
                new Row(null, "/dashboard", "http://192.168.3.50:9000/dashboard"),
                new Row("/api", null, "http://192.168.3.50:9000/api"))) {
            ReverseProxyRoute route = versionRoute(null, null).toBuilder()
                .pathPrefix(row.pathPrefix()).rootRedirectPath(row.rootRedirect()).build();
            List<String> asked = new ArrayList<>();
            ForProbingServiceSignIn prober = url -> {
                asked.add(url);
                return Optional.of(asked.size() == 1
                    ? new ServiceProbeAnswer(302, null, "/admin/", "text/html", "")
                    : new ServiceProbeAnswer(302, null, "/admin/login", "text/html", ""));
            };

            OwnSignIn signIn = route.detectOwnSignIn(prober, NOW);

            assertThat(asked).as(row.firstUrl()).containsExactly(row.firstUrl(), "http://192.168.3.50:9000/admin/");
            assertThat(signIn.kind()).isEqualTo(OwnSignIn.Kind.SIGN_IN_PAGE);
        }
    }

    /** A <b>Service call</b> goes to the backend itself, with the credential Vaier would hand it; a stream has no API. */
    @Test
    void call_asksTheBackendAtItsOwnAddress_withTheCredentialVaierHolds_andAStreamIsRefused() {
        List<String> seen = new ArrayList<>();
        ForCallingServices caller = (url, call, authorization) -> {
            seen.add(call.method() + " " + url + " " + authorization);
            return new ServiceCallAnswer(200, null, new byte[0], false);
        };
        ServiceCredential credential = new ServiceCredential("vaier", "s3cret");

        versionRoute(null, null).call(caller, ServiceCall.proposed("POST", "/rest/items/PoolPump", "ON"),
            Optional.of(credential));
        versionRoute(null, null).call(caller, ServiceCall.read("/rest/items"), Optional.empty());

        assertThat(seen).containsExactly(
            "POST http://192.168.3.50:9000/rest/items/PoolPump " + credential.authorizationHeader(),
            "GET http://192.168.3.50:9000/rest/items null");
        ReverseProxyRoute stream = versionRoute(null, null).toBuilder().stream(true).build();
        assertThatThrownBy(() -> stream.call(caller, ServiceCall.read("/rest"), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stream");
        assertThat(seen).hasSize(2);
    }

    private static ReverseProxyRoute versionRoute(String endpoint, String property) {
        return ReverseProxyRoute.builder().name("r").domainName("app.example.com")
            .address("192.168.3.50").port(9000).service("svc").isLanService(true).protocol("http")
            .versionEndpoint(endpoint).versionProperty(property).build();
    }

    private static ReverseProxyRoute pathVersionRoute(String pathPrefix, String endpoint, String property) {
        return ReverseProxyRoute.builder().name("r").domainName("app.example.com")
            .address("192.168.3.50").port(9000).service("svc").isLanService(true).protocol("http")
            .pathPrefix(pathPrefix).versionEndpoint(endpoint).versionProperty(property).build();
    }

    // --- displayName ---

    @Test
    void displayName_fallsBackToVaierServerWhenNoPeerMatches() {
        record Row(String description, String host, String address, int port, String expected) {}
        List<Row> rows = List.of(
            new Row("vaier-server-hosted service returns subdomain @ Vaier server",
                "pihole.example.com", "pihole", 8080, "pihole @ Vaier server"),
            new Row("unknown address falls back to Vaier server",
                "app.example.com", "10.13.13.5", 8080, "app @ Vaier server")
        );

        for (Row row : rows) {
            ReverseProxyRoute route = route(row.host(), row.address(), row.port());
            ForResolvingPeerIds resolver = ip -> ip;

            String name = route.displayName("example.com", List.of(), List.of(), resolver);

            assertThat(name).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void displayName_peerService_stripsPeerSuffixFromSubdomain() {
        ReverseProxyRoute route = route("pihole.myserver.example.com", "10.13.13.2", 8080);
        VpnClient peer = connectedPeer("10.13.13.2/32");
        ForResolvingPeerIds resolver = ip -> "10.13.13.2".equals(ip) ? "myserver" : ip;

        String name = route.displayName("example.com", List.of(), List.of(peer), resolver);

        assertThat(name).isEqualTo("pihole @ myserver");
    }

    @Test
    void displayName_peerService_stripsPeerSuffixLeniently() {
        record Row(String description, String host, String address, int port, String peerId, String peerDisplayName, String expected) {}
        List<Row> rows = List.of(
            // Regression from the peer id/name split: the ".<peer>" disambiguation suffix in the
            // DNS name is the immutable peer id (a slug), never the editable display name. The
            // strip must match the id — otherwise a renamed peer shows "openhab.apalveien5".
            new Row("strips peer id suffix even when display name differs",
                "openhab.apalveien5.example.com", "10.13.13.5", 8080, "apalveien5", "Apalveien 5", "openhab @ Apalveien 5"),
            // The operator hand-types the ".<peer>" suffix: here "colina27" while the peer id is
            // "Colina-27" and the display name "Colina 27". The strip must match leniently.
            new Row("strips hand-typed suffix that differs from id in punctuation",
                "nut.colina27.example.com", "10.13.13.3", 3001, "Colina-27", "Colina 27", "nut @ Colina 27")
        );

        for (Row row : rows) {
            ReverseProxyRoute route = route(row.host(), row.address(), row.port());
            VpnClient peerClient = connectedPeer(row.address() + "/32");
            PeerConfiguration peer = new PeerConfiguration(row.peerId(), row.peerDisplayName(), row.address(),
                "", MachineType.UBUNTU_SERVER, null, null, null);
            ForResolvingPeerIds resolver = ip -> ip;

            String name = route.displayName("example.com", List.of(), List.of(peerClient), resolver, List.of(peer));

            assertThat(name).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void displayName_peerStillShownWhenDisconnected() {
        // Peer presence (not connection state) controls server naming.
        ReverseProxyRoute route = route("app.myserver.example.com", "10.13.13.2", 8080);
        VpnClient disconnected = new VpnClient("pk", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0");
        ForResolvingPeerIds resolver = ip -> "10.13.13.2".equals(ip) ? "myserver" : ip;

        String name = route.displayName("example.com", List.of(), List.of(disconnected), resolver);

        assertThat(name).isEqualTo("app @ myserver");
    }

    // --- shortName + serviceLocation (#225) ---

    @Test
    void shortName_returnsTheStrippedSubdomainOnly() {
        // shortName is the bit before " @ <host>" — the operator-facing label without the host suffix.
        ReverseProxyRoute route = route("openhab.apalveien5.example.com", "10.13.13.5", 8080);
        VpnClient peerClient = connectedPeer("10.13.13.5/32");
        PeerConfiguration peer = new PeerConfiguration("apalveien5", "Apalveien 5", "10.13.13.5",
            "", MachineType.UBUNTU_SERVER, null, null, null);
        ForResolvingPeerIds resolver = ip -> ip;

        assertThat(route.shortName("example.com", List.of(peerClient), resolver, List.of(peer)))
            .isEqualTo("openhab");
    }

    @Test
    void shortName_forVaierServerRouteIsJustTheSubdomain() {
        ReverseProxyRoute route = route("pihole.example.com", "pihole", 8080);
        ForResolvingPeerIds resolver = ip -> ip;

        assertThat(route.shortName("example.com", List.of(), resolver, List.of())).isEqualTo("pihole");
    }

    @Test
    void serviceLocation_vaierServerRoute_isVaierServer() {
        ReverseProxyRoute route = route("pihole.example.com", "pihole", 8080);
        ForResolvingPeerIds resolver = ip -> ip;

        assertThat(route.serviceLocation(List.of(), resolver, List.of()))
            .isEqualTo(ReverseProxyRoute.ServiceLocation.VAIER_SERVER);
    }

    @Test
    void serviceLocation_peerHostedRoute_isPeerServer() {
        ReverseProxyRoute route = route("app.myserver.example.com", "10.13.13.2", 8080);
        VpnClient peer = connectedPeer("10.13.13.2/32");
        ForResolvingPeerIds resolver = ip -> "10.13.13.2".equals(ip) ? "myserver" : ip;

        assertThat(route.serviceLocation(List.of(peer), resolver, List.of()))
            .isEqualTo(ReverseProxyRoute.ServiceLocation.PEER_SERVER);
    }

    @Test
    void serviceLocation_lanService_isLanService() {
        ReverseProxyRoute route = lanRoute("nas.example.com", "192.168.3.50", 5000);

        assertThat(route.serviceLocation(List.of(), ip -> ip, List.of()))
            .isEqualTo(ReverseProxyRoute.ServiceLocation.LAN_SERVICE);
    }

    // --- directUrl ---

    @Test
    void directUrl_disabledFlag_returnsNull() {
        ReverseProxyRoute route = fullRoute("app.example.com", "10.13.13.2", 8080, true);
        PeerConfiguration peer = new PeerConfiguration("s", "10.13.13.2", "", MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10");
        VpnClient peerClient = connectedPeerWithEndpoint("10.13.13.2/32", "203.0.113.5");

        assertThat(route.directUrl("203.0.113.5", List.of(peer), List.of(peerClient))).isNull();
    }

    @Test
    void directUrl_callerIsPeerEndpoint_returnsLanUrl() {
        ReverseProxyRoute route = fullRoute("app.example.com", "10.13.13.2", 8080, false);
        PeerConfiguration peer = new PeerConfiguration("s", "10.13.13.2", "", MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10");
        VpnClient peerClient = connectedPeerWithEndpoint("10.13.13.2/32", "203.0.113.5");

        assertThat(route.directUrl("203.0.113.5", List.of(peer), List.of(peerClient)))
            .isEqualTo("http://192.168.1.10:8080");
    }

    @Test
    void directUrl_callerIsDifferentIp_returnsNull() {
        ReverseProxyRoute route = fullRoute("app.example.com", "10.13.13.2", 8080, false);
        PeerConfiguration peer = new PeerConfiguration("s", "10.13.13.2", "", MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10");
        VpnClient peerClient = connectedPeerWithEndpoint("10.13.13.2/32", "203.0.113.5");

        assertThat(route.directUrl("198.51.100.1", List.of(peer), List.of(peerClient))).isNull();
    }

    @Test
    void directUrl_peerMissingLanAddress_returnsNull() {
        ReverseProxyRoute route = fullRoute("app.example.com", "10.13.13.2", 8080, false);
        PeerConfiguration peer = new PeerConfiguration("s", "10.13.13.2", "");  // no lanAddress
        VpnClient peerClient = connectedPeerWithEndpoint("10.13.13.2/32", "203.0.113.5");

        assertThat(route.directUrl("203.0.113.5", List.of(peer), List.of(peerClient))).isNull();
    }

    @Test
    void directUrl_noMatchingPeer_returnsNull() {
        ReverseProxyRoute route = fullRoute("app.example.com", "10.13.13.2", 8080, false);

        assertThat(route.directUrl("203.0.113.5", List.of(), List.of())).isNull();
    }

    @Test
    void directUrl_blankCallerIp_returnsNull() {
        ReverseProxyRoute route = fullRoute("app.example.com", "10.13.13.2", 8080, false);

        assertThat(route.directUrl("", List.of(), List.of())).isNull();
        assertThat(route.directUrl(null, List.of(), List.of())).isNull();
    }

    // --- callerOnHostLan: the fact behind the direct URL, kept even when no direct URL is handed out ---

    @Test
    void callerOnHostLan_callerIsPeerEndpoint_true() {
        ReverseProxyRoute route = fullRoute("app.example.com", "10.13.13.2", 8080, false);
        PeerConfiguration peer = new PeerConfiguration("s", "10.13.13.2", "", MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10");
        VpnClient peerClient = connectedPeerWithEndpoint("10.13.13.2/32", "203.0.113.5");

        assertThat(route.callerOnHostLan("203.0.113.5", List.of(peer), List.of(peerClient))).isTrue();
    }

    @Test
    void callerOnHostLan_callerElsewhere_false() {
        ReverseProxyRoute route = fullRoute("app.example.com", "10.13.13.2", 8080, false);
        PeerConfiguration peer = new PeerConfiguration("s", "10.13.13.2", "", MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10");
        VpnClient peerClient = connectedPeerWithEndpoint("10.13.13.2/32", "203.0.113.5");

        assertThat(route.callerOnHostLan("198.51.100.1", List.of(peer), List.of(peerClient))).isFalse();
        assertThat(route.callerOnHostLan(null, List.of(peer), List.of(peerClient))).isFalse();
    }

    @Test
    void callerOnHostLan_directUrlDisabledOrNoLanAddress_stillTrue() {
        // Being in the same house is a fact about the caller; whether a LAN shortcut is handed out is a
        // separate per-route choice, and a peer without a LAN address is still a peer the caller sits beside.
        ReverseProxyRoute disabled = fullRoute("app.example.com", "10.13.13.2", 8080, true);
        PeerConfiguration peer = new PeerConfiguration("s", "10.13.13.2", "", MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10");
        PeerConfiguration noLan = new PeerConfiguration("s", "10.13.13.2", "");
        VpnClient peerClient = connectedPeerWithEndpoint("10.13.13.2/32", "203.0.113.5");

        assertThat(disabled.callerOnHostLan("203.0.113.5", List.of(peer), List.of(peerClient))).isTrue();
        assertThat(disabled.callerOnHostLan("203.0.113.5", List.of(noLan), List.of(peerClient))).isTrue();
    }

    @Test
    void callerOnHostLan_lanService_isJudgedByItsRelayPeer() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "nas-router", "nas.example.com", "192.168.3.50", 5000, "https", "nas-service");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        VpnClient relayClient = connectedPeerWithEndpoint("10.13.13.5/32", "203.0.113.5");

        assertThat(route.callerOnHostLan("203.0.113.5", List.of(relay), List.of(relayClient))).isTrue();
        assertThat(route.callerOnHostLan("198.51.100.1", List.of(relay), List.of(relayClient))).isFalse();
    }

    // --- LAN service routes (#175) ---

    @Test
    void isLanService_defaultsFalseForNonLanConstructor() {
        ReverseProxyRoute route = route("app.example.com", "10.13.13.2", 8080);

        assertThat(route.isLanService()).isFalse();
        assertThat(route.getProtocol()).isNull();
    }

    @Test
    void lanRoute_carriesIsLanServiceFlagAndProtocol() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "nas-router", "nas.example.com", "192.168.3.50", 5000, "https", "nas-service");

        assertThat(route.isLanService()).isTrue();
        assertThat(route.getProtocol()).isEqualTo("https");
        assertThat(route.getDomainName()).isEqualTo("nas.example.com");
        assertThat(route.getAddress()).isEqualTo("192.168.3.50");
        assertThat(route.getPort()).isEqualTo(5000);
    }

    @Test
    void directUrl_lanServiceMatchingRelay_returnsTargetHostUrl() {
        // For LAN services, the launchpad direct URL points at the target host:port itself.
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "nas-router", "nas.example.com", "192.168.3.50", 5000, "https", "nas-service");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        VpnClient relayClient = connectedPeerWithEndpoint("10.13.13.5/32", "203.0.113.5");

        assertThat(route.directUrl("203.0.113.5", List.of(relay), List.of(relayClient)))
            .isEqualTo("https://192.168.3.50:5000");
    }

    @Test
    void directUrl_lanServiceCallerOffNetwork_returnsNull() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "nas-router", "nas.example.com", "192.168.3.50", 5000, "http", "nas-service");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        VpnClient relayClient = connectedPeerWithEndpoint("10.13.13.5/32", "203.0.113.5");

        assertThat(route.directUrl("198.51.100.1", List.of(relay), List.of(relayClient))).isNull();
    }

    @Test
    void directUrl_lanServiceWithRootRedirectPath_appendsItToUrl() {
        ReverseProxyRoute route = new ReverseProxyRoute(
            "nut-apalveien5-router", "nut.apalveien5.example.com", "192.168.3.3", 3001, "nut-svc",
            null, null, null, null, "/devices/ups", false, true, "http");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        VpnClient relayClient = connectedPeerWithEndpoint("10.13.13.5/32", "203.0.113.5");

        assertThat(route.directUrl("203.0.113.5", List.of(relay), List.of(relayClient)))
            .isEqualTo("http://192.168.3.3:3001/devices/ups");
    }

    @Test
    void directUrl_peerServiceWithRootRedirectPath_appendsItToLanUrl() {
        ReverseProxyRoute route = new ReverseProxyRoute(
            "nut-router", "nut.example.com", "10.13.13.2", 3001, "nut-svc",
            null, null, null, null, "/devices/ups", false);
        PeerConfiguration peer = new PeerConfiguration("s", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10");
        VpnClient peerClient = connectedPeerWithEndpoint("10.13.13.2/32", "203.0.113.5");

        assertThat(route.directUrl("203.0.113.5", List.of(peer), List.of(peerClient)))
            .isEqualTo("http://192.168.1.10:3001/devices/ups");
    }

    @Test
    void directUrl_pathPrefixUsedVerbatimWithOrWithoutTrailingSlash() {
        record Row(String description, String pathPrefix, String expected) {}
        List<Row> rows = List.of(
            // No redirect set, pathPrefix has no trailing slash — the direct URL must not invent one.
            new Row("path prefix only uses the path prefix verbatim", "/builder/ui", "http://192.168.1.10:8080/builder/ui"),
            new Row("path prefix with trailing slash is preserved in the direct URL", "/builder/ui/", "http://192.168.1.10:8080/builder/ui/")
        );

        for (Row row : rows) {
            ReverseProxyRoute route = new ReverseProxyRoute(
                "r", "bmp.example.com", "10.13.13.2", 8080, "svc",
                null, null, null, null, null, false, false, null, row.pathPrefix());
            PeerConfiguration peer = new PeerConfiguration("s", "10.13.13.2", "",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10");
            VpnClient peerClient = connectedPeerWithEndpoint("10.13.13.2/32", "203.0.113.5");

            assertThat(route.directUrl("203.0.113.5", List.of(peer), List.of(peerClient)))
                .as(row.description())
                .isEqualTo(row.expected());
        }
    }

    @Test
    void displayName_lanService_usesRelayPeerNameAsServer() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "nut-router", "nut.apalveien5.example.com", "192.168.3.3", 3001, "http", "nut-svc");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        ForResolvingPeerIds resolver = ip -> ip;

        String name = route.displayName("example.com", List.of(), List.of(), resolver, List.of(relay));

        assertThat(name).isEqualTo("nut @ apalveien5");
    }

    // --- lanServerName (#234 follow-up) ---
    // For LAN-routed services, the published-services page shows the LAN host's display name in
    // the card sub-line — the relay peer is already named by the section heading. This is the
    // single piece of LAN-server identity the route surfaces; the relay still owns the routing.

    @Test
    void lanServerName_lanServiceWithMatchingHost_returnsItsDisplayName() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "nas-router", "nas.example.com", "192.168.3.50", 5000, "http", "nas-svc");
        LanServer nas = new LanServer("nas", "192.168.3.50", false, null);

        assertThat(route.lanServerName(List.of(nas))).contains("nas");
    }

    @Test
    void lanServerName_lanServiceWithUnknownAddress_isEmpty() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "nas-router", "nas.example.com", "192.168.3.50", 5000, "http", "nas-svc");

        assertThat(route.lanServerName(List.of())).isEmpty();
    }

    @Test
    void lanServerName_peerHostedRoute_isEmpty() {
        // Non-LAN routes don't carry a LAN-host concept; the heading already names the peer.
        ReverseProxyRoute route = route("app.example.com", "10.13.13.2", 8080);
        LanServer unrelated = new LanServer("nas", "192.168.3.50", false, null);

        assertThat(route.lanServerName(List.of(unrelated))).isEmpty();
    }

    // --- routerName / serviceName / dnsNameFromRouterName (#229) ---

    @Test
    void routerName_hostOnly_dotsAreDashed_withRouterSuffix() {
        assertThat(ReverseProxyRoute.routerName("app.example.com", null))
            .isEqualTo("app-example-com-router");
    }

    @Test
    void routerName_pathBased_includesSluggedPath() {
        record Row(String description, String path, String expected) {}
        List<Row> rows = List.of(
            new Row("single-segment path becomes a slugged suffix", "/grafana", "svc-example-com-grafana-router"),
            new Row("multi-segment path slashes become dashes", "/builder/ui", "svc-example-com-builder-ui-router")
        );

        for (Row row : rows) {
            assertThat(ReverseProxyRoute.routerName("svc.example.com", row.path())).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void routerName_blankPath_isSameAsHostOnly() {
        assertThat(ReverseProxyRoute.routerName("svc.example.com", ""))
            .isEqualTo(ReverseProxyRoute.routerName("svc.example.com", null));
    }

    @Test
    void serviceName_mirrorsRouterNameWithServiceSuffix() {
        assertThat(ReverseProxyRoute.serviceName("svc.example.com", "/grafana"))
            .isEqualTo("svc-example-com-grafana-service");
    }

    @Test
    void dnsNameFromRouterName_invertsRouterName_replacingDashesWithDots() {
        assertThat(ReverseProxyRoute.dnsNameFromRouterName("app-example-com-router"))
            .isEqualTo("app.example.com");
    }

    @Test
    void dnsNameFromRouterName_nullOrNonRouter_returnsNull() {
        assertThat(ReverseProxyRoute.dnsNameFromRouterName(null)).isNull();
        assertThat(ReverseProxyRoute.dnsNameFromRouterName("app-example-com-service")).isNull();
    }

    // "is this an auth middleware?" moved to AuthMode (#341) — see AuthModeTest. It lived here as a
    // substring keyword match, which read a CrowdSec bouncer as an authenticator.

    // --- helpers ---

    private static ReverseProxyRoute route(String domain, String address, int port) {
        return new ReverseProxyRoute("route", domain, address, port, "svc", null);
    }

    private static ReverseProxyRoute lanRoute(String domain, String address, int port) {
        return new ReverseProxyRoute("route", domain, address, port, "svc", null,
            null, null, null, null, false, true, "http");
    }

    private static ReverseProxyRoute fullRoute(String domain, String address, int port, boolean directUrlDisabled) {
        return new ReverseProxyRoute("route", domain, address, port, "svc", null,
            null, null, null, null, directUrlDisabled);
    }

    private static DockerService runningLocal(String name, int port) {
        return new DockerService("id", name, "image", "v",
            List.of(new PortMapping(port, port, "tcp", "0.0.0.0")), List.of(), "running");
    }

    private static VpnClient connectedPeer(String allowedIps) {
        String recent = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        return new VpnClient("pk", allowedIps, "1.2.3.4", "51820", recent, "0", "0");
    }

    private static VpnClient connectedPeerWithEndpoint(String allowedIps, String endpointIp) {
        String recent = String.valueOf(System.currentTimeMillis() / 1000 - 60);
        return new VpnClient("pk", allowedIps, endpointIp, "51820", recent, "0", "0");
    }

    // --- root redirect: a redirect that lands back on the root is a loop, not a redirect ---

    @Test
    void anEmptyRootRedirect_isNoRedirectAtAll() {
        // Traefik's rule matches the bare host with or without the trailing slash, so a replacement of the
        // host itself re-matches and redirects forever. Five live routes were doing exactly that.
        assertThat(ReverseProxyRoute.normaliseRootRedirectPath("")).isNull();
        assertThat(ReverseProxyRoute.normaliseRootRedirectPath("   ")).isNull();
        assertThat(ReverseProxyRoute.normaliseRootRedirectPath(null)).isNull();
    }

    @Test
    void aRootRedirectToTheRoot_isNoRedirectAtAll() {
        assertThat(ReverseProxyRoute.normaliseRootRedirectPath("/")).isNull();
    }

    @Test
    void aRealRootRedirect_survives_andGainsItsLeadingSlash() {
        assertThat(ReverseProxyRoute.normaliseRootRedirectPath("/admin")).isEqualTo("/admin");
        assertThat(ReverseProxyRoute.normaliseRootRedirectPath("admin")).isEqualTo("/admin");
        assertThat(ReverseProxyRoute.normaliseRootRedirectPath(" /dashboard/ ")).isEqualTo("/dashboard/");
    }
    // --- a stream: a TCP service published by SNI on 443, with no HTTP for anything to look inside ---
    //
    // Traefik terminates TLS by HostSNI and forwards the bytes unchanged. Nothing downstream of the
    // handshake is an HTTP request, so oauth2-proxy has no request to gate and the CrowdSec bouncer has
    // no request to inspect — which is why the refusals below are the domain's and not the UI's.

    private static ReverseProxyRoute streamRoute() {
        return ReverseProxyRoute.builder()
            .name("mqtt-example-com-router").domainName("mqtt.example.com").address("172.20.0.1")
            .port(1883).service("mqtt-example-com-service").stream(true)
            .entryPoints(List.of("websecure"))
            .build();
    }

    @Test
    void publishingAStream_refusesSocialLogin() {
        assertThatThrownBy(() -> ReverseProxyRoute.validateStreamPublication(AuthMode.SOCIAL, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stream");
    }

    @Test
    void publishingAStream_refusesAPathPrefix_becauseHostSniMatchesTheHostAndNothingElse() {
        assertThatThrownBy(() -> ReverseProxyRoute.validateStreamPublication(AuthMode.NONE, "/mqtt", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishingAStream_refusesARootRedirect_becauseThereIsNoUrlToRedirect() {
        assertThatThrownBy(() -> ReverseProxyRoute.validateStreamPublication(AuthMode.NONE, null, "/dashboard"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishingAPlainStream_isAllowed() {
        assertThatCode(() -> ReverseProxyRoute.validateStreamPublication(AuthMode.NONE, null, null))
            .doesNotThrowAnyException();
    }

    @Test
    void aStream_isReachedAtItsNameOn443() {
        // The whole point of the feature: the client dials the published name on the TLS port, and
        // Traefik hands the decrypted bytes to the backend port unchanged.
        assertThat(streamRoute().connectAddress()).isEqualTo("mqtt.example.com:443");
    }

    @Test
    void anHttpRoute_hasNoConnectAddress_itHasAUrl() {
        assertThat(routeWithMiddlewares(List.of("vaier-errors")).connectAddress()).isNull();
    }

    @Test
    void aStream_isNeverALaunchpadTile_becauseThereIsNoUrlToOpen() {
        assertThat(streamRoute().launchpadVisibility(State.OK))
            .isEqualTo(LaunchpadVisibility.NOT_VISIBLE);
    }
    // --- what a route may carry after it is published --------------------------------------------------
    //
    // Every per-route setting Vaier offers is an HTTP one, so a stream can carry none of them. Before this
    // rule the sign-in picker was guarded and the other five were not: a redirect or an auth toggle reached
    // the adapter, which looks for the router in the http: section, does not find a stream there, and
    // answers RuntimeException("Router not found") — a 500 for what is really a bad request. The launchpad
    // and version-probe settings were worse: they wrote sidecars nothing would ever read.

    @Test
    void aStream_refusesEverySettingARouteCanCarry() {
        for (RouteSetting setting : RouteSetting.values()) {
            assertThatThrownBy(() -> streamRoute().validateUpdate(Set.of(setting)))
                .as("a stream refusing %s", setting)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stream");
        }
    }

    @Test
    void theRefusal_namesTheSettingItRefused() {
        assertThatThrownBy(() -> streamRoute().validateUpdate(Set.of(RouteSetting.ROOT_REDIRECT)))
            .hasMessageContaining("root redirect");
        assertThatThrownBy(() -> streamRoute().validateUpdate(Set.of(RouteSetting.AUTH_MODE)))
            .hasMessageContaining("login");
    }

    @Test
    void aStream_acceptsAnUpdateThatChangesNothing() {
        assertThatCode(() -> streamRoute().validateUpdate(Set.of())).doesNotThrowAnyException();
    }

    @Test
    void anHttpRoute_carriesEverySetting() {
        assertThatCode(() -> routeWithMiddlewares(List.of("vaier-errors"))
            .validateUpdate(Set.of(RouteSetting.values()))).doesNotThrowAnyException();
    }
    // --- the subdomain is a DNS label, and now has to look like one ------------------------------------
    //
    // The operator's subdomain is interpolated straight into a Traefik rule. In an HTTP rule a stray
    // backtick would break the route; in a stream's HostSNI rule it could WIDEN one — close the quote,
    // append your own matcher, and a TCP router on 443 swallows every TLS connection the box takes,
    // console included. "Not blank" was the only check standing between an operator's typo and that.

    @ParameterizedTest
    @ValueSource(strings = {"mqtt", "printer", "nut2", "portainer-nas", "printer.colina27",
                            "netdata.nuc02", "a"})
    void anOrdinarySubdomain_isAccepted(String subdomain) {
        assertThatCode(() -> ReverseProxyRoute.validateSubdomain(subdomain)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "mqtt`) || HostSNI(`",   // the one that matters: widen a TCP rule to catch everything on 443
        "mqtt`",
        "MQTT",                   // DNS labels are case-insensitive; two spellings of one name is a trap
        "my_broker",
        "-mqtt", "mqtt-",
        "mqtt broker",
        "mqtt..broker",
        "mqtt/../vaier",
        ".mqtt", "mqtt.",
        "mqtt$",
    })
    void aSubdomainThatIsNotADnsLabel_isRefused(String subdomain) {
        assertThatThrownBy(() -> ReverseProxyRoute.validateSubdomain(subdomain))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void aMissingSubdomain_isRefused(String subdomain) {
        assertThatThrownBy(() -> ReverseProxyRoute.validateSubdomain(subdomain))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLabelLongerThanDnsAllows_isRefused() {
        assertThatThrownBy(() -> ReverseProxyRoute.validateSubdomain("a".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> ReverseProxyRoute.validateSubdomain("a".repeat(63)))
            .doesNotThrowAnyException();
    }

    // --- #351: the CrowdSec bouncer rides every route Vaier writes, first ---

    @Test
    void aWrittenRouteChain_startsWithTheBouncer_thenAuth_thenRedirect_thenTheOfflinePage() {
        record Row(AuthMode mode, String redirect, List<String> expected) {}
        for (Row row : List.of(
                new Row(AuthMode.NONE, null, List.of("crowdsec-bouncer@file", "vaier-errors")),
                new Row(AuthMode.NONE, "app-redirect",
                    List.of("crowdsec-bouncer@file", "app-redirect", "vaier-errors")),
                new Row(AuthMode.SOCIAL, "app-redirect", List.of("crowdsec-bouncer@file",
                    "oauth2-signin", "oauth2-authn", "vaier-authz", "app-redirect", "vaier-errors")))) {
            assertThat(ReverseProxyRoute.middlewareChain(row.mode(), row.redirect()))
                .as(row.toString()).containsExactlyElementsOf(row.expected());
        }
    }

    @Test
    void anAuthModeSwitch_swapsOnlyTheAuthLinks_andKeepsTheBouncerFirst() {
        List<String> social = List.of("crowdsec-bouncer@file",
            "oauth2-signin", "oauth2-authn", "vaier-authz", "app-redirect", "vaier-errors");

        assertThat(ReverseProxyRoute.rechained(social, AuthMode.NONE))
            .containsExactly("crowdsec-bouncer@file", "app-redirect", "vaier-errors");
        // A chain written before the bouncer existed comes out of a switch with it, in front.
        assertThat(ReverseProxyRoute.rechained(List.of("vaier-errors"), AuthMode.SOCIAL))
            .containsExactly("crowdsec-bouncer@file", "oauth2-signin", "oauth2-authn", "vaier-authz",
                "vaier-errors");
    }

    @Test
    void anOlderChain_isBroughtUpToDate_bouncerFirstAndOfflinePageLast_eachExactlyOnce() {
        record Row(List<String> existing, List<String> expected) {}
        for (Row row : List.of(
                new Row(List.of(), List.of("crowdsec-bouncer@file", "vaier-errors")),
                new Row(List.of("auth-middleware", "legacy-redirect"),
                    List.of("crowdsec-bouncer@file", "auth-middleware", "legacy-redirect", "vaier-errors")),
                new Row(List.of("oauth2-signin", "vaier-errors"),
                    List.of("crowdsec-bouncer@file", "oauth2-signin", "vaier-errors")),
                // Anywhere but first is as good as missing: the auth hop would run before the ban.
                new Row(List.of("oauth2-signin", "crowdsec-bouncer@file", "vaier-errors"),
                    List.of("crowdsec-bouncer@file", "oauth2-signin", "vaier-errors")),
                new Row(List.of("crowdsec-bouncer@file", "vaier-errors"),
                    List.of("crowdsec-bouncer@file", "vaier-errors")))) {
            assertThat(ReverseProxyRoute.upToDateChain(row.existing()))
                .as(row.existing().toString()).containsExactlyElementsOf(row.expected());
        }
    }
}
