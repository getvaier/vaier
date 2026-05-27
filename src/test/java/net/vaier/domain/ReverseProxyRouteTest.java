package net.vaier.domain;

import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.Server.State;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForProbingServiceVersion;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ReverseProxyRouteTest {

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
    void normalisePathPrefix_preservesOperatorTrailingSlash() {
        // The operator's trailing slash is part of their intent — backend SPAs sometimes serve
        // different content for /path vs /path/, so we keep the slash they typed instead of
        // silently dropping it (issue: bmp.native.corporater.dev/builder/ui).
        assertThat(ReverseProxyRoute.normalisePathPrefix("/auth/")).isEqualTo("/auth/");
        assertThat(ReverseProxyRoute.normalisePathPrefix("/builder/ui/")).isEqualTo("/builder/ui/");
    }

    @Test
    void normalisePathPrefix_preservesGoodValueUnchanged() {
        assertThat(ReverseProxyRoute.normalisePathPrefix("/auth")).isEqualTo("/auth");
        assertThat(ReverseProxyRoute.normalisePathPrefix("/CorpoWebserver")).isEqualTo("/CorpoWebserver");
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
    void launchpadDisplayName_hostOnly_returnsSubdomain() {
        ReverseProxyRoute route = route("grafana.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadDisplayName("example.com")).isEqualTo("grafana");
    }

    @Test
    void launchpadDisplayName_hostOnlyOnNestedSubdomain_returnsFirstLabel() {
        // grafana.myserver.example.com → "grafana"
        ReverseProxyRoute route = route("grafana.myserver.example.com", "10.13.13.2", 8080);

        assertThat(route.launchpadDisplayName("example.com")).isEqualTo("grafana");
    }

    @Test
    void launchpadDisplayName_pathBased_returnsLastPathSegment() {
        ReverseProxyRoute route = pathRoute("svc.example.com", "/grafana");

        assertThat(route.launchpadDisplayName("example.com")).isEqualTo("grafana");
    }

    @Test
    void launchpadDisplayName_pathBasedNested_returnsFinalSegment() {
        ReverseProxyRoute route = pathRoute("svc.example.com", "/api/v1");

        assertThat(route.launchpadDisplayName("example.com")).isEqualTo("v1");
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

    // --- launchpadFaviconQuery (domain owns the favicon lookup identity) ---

    @Test
    void launchpadFaviconQuery_hostOnly_emitsHostParamOnly() {
        ReverseProxyRoute route = route("grafana.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadFaviconQuery()).isEqualTo("host=grafana.example.com");
    }

    @Test
    void launchpadFaviconQuery_pathBased_includesPathPrefix() {
        ReverseProxyRoute route = pathRoute("services.example.com", "/grafana");

        assertThat(route.launchpadFaviconQuery())
            .isEqualTo("host=services.example.com&pathPrefix=%2Fgrafana");
    }

    @Test
    void launchpadFaviconQuery_pathBasedSiblings_differByPathPrefix() {
        // The whole point: siblings on one FQDN must produce distinct queries so the
        // launchpad doesn't fight over a single cache entry.
        ReverseProxyRoute grafana = pathRoute("services.example.com", "/grafana");
        ReverseProxyRoute jenkins = pathRoute("services.example.com", "/jenkins");

        assertThat(grafana.launchpadFaviconQuery())
            .isNotEqualTo(jenkins.launchpadFaviconQuery());
    }

    // --- launchpadVisibility (domain rule consolidating every reason a route is shown/hidden) ---

    @Test
    void launchpadVisibility_dnsOkAndHostOk_visibleActive() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadVisibility(DnsState.OK, State.OK))
            .isEqualTo(LaunchpadVisibility.VISIBLE_ACTIVE);
    }

    @Test
    void launchpadVisibility_dnsOkAndHostUnreachable_visibleInactive() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadVisibility(DnsState.OK, State.UNREACHABLE))
            .isEqualTo(LaunchpadVisibility.VISIBLE_INACTIVE);
    }

    @Test
    void launchpadVisibility_dnsNotPropagated_notVisible() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadVisibility(DnsState.NON_EXISTING, State.OK))
            .isEqualTo(LaunchpadVisibility.NOT_VISIBLE);
    }

    @Test
    void launchpadVisibility_hiddenFromLaunchpad_notVisibleEvenWhenHealthy() {
        ReverseProxyRoute hidden = new ReverseProxyRoute("r", "app.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, null, true);

        assertThat(hidden.launchpadVisibility(DnsState.OK, State.OK))
            .isEqualTo(LaunchpadVisibility.NOT_VISIBLE);
    }

    // --- caller-authenticated gating (issue #207) ---

    @Test
    void launchpadVisibility_authProtectedAndCallerAnonymous_notVisible() {
        ReverseProxyRoute route = new ReverseProxyRoute("r", "internal.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null));

        assertThat(route.launchpadVisibility(DnsState.OK, State.OK, false))
            .isEqualTo(LaunchpadVisibility.NOT_VISIBLE);
    }

    @Test
    void launchpadVisibility_authProtectedAndCallerAuthenticated_followsHealthRules() {
        ReverseProxyRoute route = new ReverseProxyRoute("r", "internal.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null));

        assertThat(route.launchpadVisibility(DnsState.OK, State.OK, true))
            .isEqualTo(LaunchpadVisibility.VISIBLE_ACTIVE);
        assertThat(route.launchpadVisibility(DnsState.OK, State.UNREACHABLE, true))
            .isEqualTo(LaunchpadVisibility.VISIBLE_INACTIVE);
    }

    @Test
    void launchpadVisibility_publicRoute_visibleToAnonymousCaller() {
        ReverseProxyRoute route = route("public.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadVisibility(DnsState.OK, State.OK, false))
            .isEqualTo(LaunchpadVisibility.VISIBLE_ACTIVE);
    }

    @Test
    void launchpadVisibility_hiddenWins_overCallerAuthenticated() {
        ReverseProxyRoute hidden = new ReverseProxyRoute("r", "app.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, null, true);

        assertThat(hidden.launchpadVisibility(DnsState.OK, State.OK, true))
            .isEqualTo(LaunchpadVisibility.NOT_VISIBLE);
    }

    // --- domain rules over existing-routes lists ---

    @Test
    void hasSiblingOnHost_emptyList_false() {
        assertThat(ReverseProxyRoute.hasSiblingOnHost(List.of(), "bmp.example.com")).isFalse();
    }

    @Test
    void hasSiblingOnHost_matchingDomain_true_regardlessOfPath() {
        ReverseProxyRoute existing = pathRoute("bmp.example.com", "/auth");
        assertThat(ReverseProxyRoute.hasSiblingOnHost(List.of(existing), "bmp.example.com")).isTrue();
    }

    @Test
    void hasSiblingOnHost_differentDomain_false() {
        ReverseProxyRoute existing = pathRoute("other.example.com", "/auth");
        assertThat(ReverseProxyRoute.hasSiblingOnHost(List.of(existing), "bmp.example.com")).isFalse();
    }

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

    // --- dnsState ---

    @Test
    void dnsState_cnameRecordMatchesDomainName_returnsOk() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);
        List<DnsRecord> records = List.of(
            new DnsRecord("app.example.com", DnsRecordType.CNAME, 300L, List.of("target"))
        );

        assertThat(route.dnsState(records)).isEqualTo(DnsState.OK);
    }

    @Test
    void dnsState_aRecordMatchesDomainName_returnsOk() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);
        List<DnsRecord> records = List.of(
            new DnsRecord("app.example.com", DnsRecordType.A, 300L, List.of("1.2.3.4"))
        );

        assertThat(route.dnsState(records)).isEqualTo(DnsState.OK);
    }

    @Test
    void dnsState_noMatchingRecord_returnsNonExisting() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);
        List<DnsRecord> records = List.of(
            new DnsRecord("other.example.com", DnsRecordType.CNAME, 300L, List.of())
        );

        assertThat(route.dnsState(records)).isEqualTo(DnsState.NON_EXISTING);
    }

    @Test
    void dnsState_onlyNonMatchingTypes_returnsNonExisting() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);
        List<DnsRecord> records = List.of(
            new DnsRecord("app.example.com", DnsRecordType.TXT, 300L, List.of())
        );

        assertThat(route.dnsState(records)).isEqualTo(DnsState.NON_EXISTING);
    }

    // --- dnsState(provider) / launchpadUrl / protocol (#231) ---

    @Test
    void dnsState_manualProvider_isAlwaysOkRegardlessOfRecords() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.dnsState(List.of(), DnsProvider.MANUAL)).isEqualTo(DnsState.OK);
    }

    @Test
    void dnsState_route53Provider_delegatesToTheRecordLookup() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.dnsState(List.of(), DnsProvider.ROUTE53)).isEqualTo(DnsState.NON_EXISTING);
    }

    @Test
    void launchpadUrl_publicRoute_isTheDirectHttpsUrl() {
        ReverseProxyRoute route = route("app.example.com", "10.0.0.1", 8080);

        assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
            .isEqualTo("https://app.example.com");
    }

    @Test
    void launchpadUrl_authProtectedRoute_routesThroughTheAutheliaLoginUrl() {
        ReverseProxyRoute route = new ReverseProxyRoute("r", "internal.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null));

        assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
            .isEqualTo("https://login.example.com/?rd=https%3A%2F%2Finternal.example.com%2F");
    }

    @Test
    void launchpadUrl_pathPrefixWithTrailingSlash_isEmittedVerbatim() {
        // Operator typed `/builder/ui/` — we don't strip the slash, and we don't auto-add one
        // either; the launchpad URL uses the pathPrefix as the landing path as-is.
        ReverseProxyRoute route = new ReverseProxyRoute("r", "bmp.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/builder/ui/");

        assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
            .isEqualTo("https://bmp.example.com/builder/ui/");
    }

    @Test
    void launchpadUrl_pathPrefixWithoutTrailingSlash_isEmittedVerbatim() {
        // Operator typed `/builder/ui` (no slash) — emitted as-is. If the backend needs a slash,
        // the operator can express that via a redirect.
        ReverseProxyRoute route = new ReverseProxyRoute("r", "bmp.example.com", "10.0.0.1", 8080, "svc",
            null, null, null, null, null, false, false, null, "/builder/ui");

        assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
            .isEqualTo("https://bmp.example.com/builder/ui");
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
    void launchpadUrl_authProtectedRouteWithRedirect_encodesRedirectAsRdTarget() {
        ReverseProxyRoute route = new ReverseProxyRoute("r", "bmp.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null, null,
            "/builder/ui/", false, false, null, "/builder/ui");

        assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
            .isEqualTo("https://login.example.com/?rd=https%3A%2F%2Fbmp.example.com%2Fbuilder%2Fui%2F");
    }

    @Test
    void launchpadUrl_authProtectedRouteWithPathPrefix_encodesPathPrefixAsRdTarget() {
        ReverseProxyRoute route = new ReverseProxyRoute("r", "bmp.example.com", "10.0.0.1", 8080, "svc",
            new ReverseProxyRoute.AuthInfo("forwardAuth", null, null), null, null, null,
            null, false, false, null, "/builder/ui/");

        assertThat(route.launchpadUrl(null, List.of(), List.of(), "example.com"))
            .isEqualTo("https://login.example.com/?rd=https%3A%2F%2Fbmp.example.com%2Fbuilder%2Fui%2F");
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
    void hostState_connectedVpnPeerMatchingAddress_returnsOk() {
        ReverseProxyRoute route = route("app.example.com", "10.13.13.2", 8080);
        VpnClient connected = connectedPeer("10.13.13.2/32");

        assertThat(route.hostState(List.of(), List.of(connected))).isEqualTo(State.OK);
    }

    @Test
    void hostState_staleVpnPeer_returnsUnreachable() {
        ReverseProxyRoute route = route("app.example.com", "10.13.13.2", 8080);
        VpnClient stale = new VpnClient("pk", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0");

        assertThat(route.hostState(List.of(), List.of(stale))).isEqualTo(State.UNREACHABLE);
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
    void hostState_lanService_relayConnected_lanHostOk_returnsOk() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "nas.example.com", "192.168.3.50", 5000, "http", "svc");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        VpnClient connectedRelay = connectedPeer("10.13.13.5/32");

        assertThat(route.hostState(List.of(), List.of(connectedRelay), List.of(relay), null,
            Map.of("192.168.3.50", Reachability.OK))).isEqualTo(State.OK);
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

        assertThat(route.launchpadVisibility(DnsState.OK, State.UNKNOWN))
            .isEqualTo(LaunchpadVisibility.VISIBLE_ACTIVE);
    }

    @Test
    void launchpadVisibility_hostStateUnreachable_returnsVisibleInactive() {
        ReverseProxyRoute route = route("app.example.com", "10.13.13.2", 8080);

        assertThat(route.launchpadVisibility(DnsState.OK, State.UNREACHABLE))
            .isEqualTo(LaunchpadVisibility.VISIBLE_INACTIVE);
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

    private static ReverseProxyRoute versionRoute(String endpoint, String property) {
        return new ReverseProxyRoute("r", "app.example.com", "192.168.3.50", 9000, "svc", null,
            null, null, null, null, false, true, "http", null, false, null, endpoint, property);
    }

    // --- displayName ---

    @Test
    void displayName_vaierServerService_returnsSubdomainAtVaierServer() {
        ReverseProxyRoute route = route("pihole.example.com", "pihole", 8080);
        ForResolvingPeerNames resolver = ip -> ip;

        String name = route.displayName("example.com", List.of(), List.of(), resolver);

        assertThat(name).isEqualTo("pihole @ Vaier server");
    }

    @Test
    void displayName_peerService_stripsPeerSuffixFromSubdomain() {
        ReverseProxyRoute route = route("pihole.myserver.example.com", "10.13.13.2", 8080);
        VpnClient peer = connectedPeer("10.13.13.2/32");
        ForResolvingPeerNames resolver = ip -> "10.13.13.2".equals(ip) ? "myserver" : ip;

        String name = route.displayName("example.com", List.of(), List.of(peer), resolver);

        assertThat(name).isEqualTo("pihole @ myserver");
    }

    @Test
    void displayName_peerService_stripsPeerIdSuffixEvenWhenDisplayNameDiffers() {
        // Regression from the peer id/name split: the ".<peer>" disambiguation suffix in the
        // DNS name is the immutable peer id (a slug), never the editable display name. The
        // strip must match the id — otherwise a renamed peer shows "openhab.apalveien5".
        ReverseProxyRoute route = route("openhab.apalveien5.example.com", "10.13.13.5", 8080);
        VpnClient peerClient = connectedPeer("10.13.13.5/32");
        PeerConfiguration peer = new PeerConfiguration("apalveien5", "Apalveien 5", "10.13.13.5",
            "", MachineType.UBUNTU_SERVER, null, null, null);
        ForResolvingPeerNames resolver = ip -> ip;

        String name = route.displayName("example.com", List.of(), List.of(peerClient), resolver, List.of(peer));

        assertThat(name).isEqualTo("openhab @ Apalveien 5");
    }

    @Test
    void displayName_peerService_stripsHandTypedSuffixThatDiffersFromIdInPunctuation() {
        // The operator hand-types the ".<peer>" suffix: here "colina27" while the peer id is
        // "Colina-27" and the display name "Colina 27". The strip must match leniently.
        ReverseProxyRoute route = route("nut.colina27.example.com", "10.13.13.3", 3001);
        VpnClient peerClient = connectedPeer("10.13.13.3/32");
        PeerConfiguration peer = new PeerConfiguration("Colina-27", "Colina 27", "10.13.13.3",
            "", MachineType.UBUNTU_SERVER, null, null, null);
        ForResolvingPeerNames resolver = ip -> ip;

        String name = route.displayName("example.com", List.of(), List.of(peerClient), resolver, List.of(peer));

        assertThat(name).isEqualTo("nut @ Colina 27");
    }

    @Test
    void displayName_unknownAddress_fallsBackToVaierServer() {
        ReverseProxyRoute route = route("app.example.com", "10.13.13.5", 8080);
        ForResolvingPeerNames resolver = ip -> ip;

        String name = route.displayName("example.com", List.of(), List.of(), resolver);

        assertThat(name).isEqualTo("app @ Vaier server");
    }

    @Test
    void displayName_peerStillShownWhenDisconnected() {
        // Peer presence (not connection state) controls server naming.
        ReverseProxyRoute route = route("app.myserver.example.com", "10.13.13.2", 8080);
        VpnClient disconnected = new VpnClient("pk", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0");
        ForResolvingPeerNames resolver = ip -> "10.13.13.2".equals(ip) ? "myserver" : ip;

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
        ForResolvingPeerNames resolver = ip -> ip;

        assertThat(route.shortName("example.com", List.of(peerClient), resolver, List.of(peer)))
            .isEqualTo("openhab");
    }

    @Test
    void shortName_forVaierServerRouteIsJustTheSubdomain() {
        ReverseProxyRoute route = route("pihole.example.com", "pihole", 8080);
        ForResolvingPeerNames resolver = ip -> ip;

        assertThat(route.shortName("example.com", List.of(), resolver, List.of())).isEqualTo("pihole");
    }

    @Test
    void serviceLocation_vaierServerRoute_isVaierServer() {
        ReverseProxyRoute route = route("pihole.example.com", "pihole", 8080);
        ForResolvingPeerNames resolver = ip -> ip;

        assertThat(route.serviceLocation(List.of(), resolver, List.of()))
            .isEqualTo(ReverseProxyRoute.ServiceLocation.VAIER_SERVER);
    }

    @Test
    void serviceLocation_peerHostedRoute_isPeerServer() {
        ReverseProxyRoute route = route("app.myserver.example.com", "10.13.13.2", 8080);
        VpnClient peer = connectedPeer("10.13.13.2/32");
        ForResolvingPeerNames resolver = ip -> "10.13.13.2".equals(ip) ? "myserver" : ip;

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
    void directUrl_pathPrefixOnly_usesPathPrefixVerbatim() {
        // No redirect set, pathPrefix has no trailing slash — the direct URL must not invent one.
        ReverseProxyRoute route = new ReverseProxyRoute(
            "r", "bmp.example.com", "10.13.13.2", 8080, "svc",
            null, null, null, null, null, false, false, null, "/builder/ui");
        PeerConfiguration peer = new PeerConfiguration("s", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10");
        VpnClient peerClient = connectedPeerWithEndpoint("10.13.13.2/32", "203.0.113.5");

        assertThat(route.directUrl("203.0.113.5", List.of(peer), List.of(peerClient)))
            .isEqualTo("http://192.168.1.10:8080/builder/ui");
    }

    @Test
    void directUrl_pathPrefixWithTrailingSlash_preservedInDirectUrl() {
        ReverseProxyRoute route = new ReverseProxyRoute(
            "r", "bmp.example.com", "10.13.13.2", 8080, "svc",
            null, null, null, null, null, false, false, null, "/builder/ui/");
        PeerConfiguration peer = new PeerConfiguration("s", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10");
        VpnClient peerClient = connectedPeerWithEndpoint("10.13.13.2/32", "203.0.113.5");

        assertThat(route.directUrl("203.0.113.5", List.of(peer), List.of(peerClient)))
            .isEqualTo("http://192.168.1.10:8080/builder/ui/");
    }

    @Test
    void displayName_lanService_usesRelayPeerNameAsServer() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "nut-router", "nut.apalveien5.example.com", "192.168.3.3", 3001, "http", "nut-svc");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        ForResolvingPeerNames resolver = ip -> ip;

        String name = route.displayName("example.com", List.of(), List.of(), resolver, List.of(relay));

        assertThat(name).isEqualTo("nut @ apalveien5");
    }

    // --- routerName / serviceName / dnsNameFromRouterName (#229) ---

    @Test
    void routerName_hostOnly_dotsAreDashed_withRouterSuffix() {
        assertThat(ReverseProxyRoute.routerName("app.example.com", null))
            .isEqualTo("app-example-com-router");
    }

    @Test
    void routerName_pathBased_includesSluggedPath() {
        assertThat(ReverseProxyRoute.routerName("svc.example.com", "/grafana"))
            .isEqualTo("svc-example-com-grafana-router");
    }

    @Test
    void routerName_multiSegmentPath_slashesBecomeDashes() {
        assertThat(ReverseProxyRoute.routerName("svc.example.com", "/builder/ui"))
            .isEqualTo("svc-example-com-builder-ui-router");
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

    // --- AuthInfo.isAuthMiddlewareName (#229) ---

    @Test
    void isAuthMiddlewareName_recognisesCommonAuthKeywords() {
        assertThat(ReverseProxyRoute.AuthInfo.isAuthMiddlewareName("authelia@docker")).isTrue();
        assertThat(ReverseProxyRoute.AuthInfo.isAuthMiddlewareName("forward-auth")).isTrue();
        assertThat(ReverseProxyRoute.AuthInfo.isAuthMiddlewareName("oauth-proxy")).isTrue();
        assertThat(ReverseProxyRoute.AuthInfo.isAuthMiddlewareName("SSO")).isTrue();
    }

    @Test
    void isAuthMiddlewareName_rejectsUnrelatedMiddleware() {
        assertThat(ReverseProxyRoute.AuthInfo.isAuthMiddlewareName("strip-prefix")).isFalse();
        assertThat(ReverseProxyRoute.AuthInfo.isAuthMiddlewareName("compress")).isFalse();
        assertThat(ReverseProxyRoute.AuthInfo.isAuthMiddlewareName(null)).isFalse();
    }

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
}
