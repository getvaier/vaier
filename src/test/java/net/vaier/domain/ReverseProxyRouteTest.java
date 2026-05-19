package net.vaier.domain;

import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.Server.State;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

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
    void normalisePathPrefix_stripsTrailingSlash() {
        assertThat(ReverseProxyRoute.normalisePathPrefix("/auth/")).isEqualTo("/auth");
        assertThat(ReverseProxyRoute.normalisePathPrefix("/builder/ui/")).isEqualTo("/builder/ui");
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
    @ValueSource(strings = {"/auth", "/builder/ui", "/CorpoWebserver", "/a-b_c.d", "/x/y/z"})
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
    void hostState_lanServiceWithRelay_relayConnected_returnsOk() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "nas.example.com", "192.168.3.50", 5000, "http", "svc");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        VpnClient connectedRelay = connectedPeer("10.13.13.5/32");

        assertThat(route.hostState(List.of(), List.of(connectedRelay), List.of(relay), null)).isEqualTo(State.OK);
    }

    @Test
    void hostState_lanServiceWithRelay_relayDisconnected_returnsUnreachable() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "nas.example.com", "192.168.3.50", 5000, "http", "svc");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");

        assertThat(route.hostState(List.of(), List.of(), List.of(relay), null)).isEqualTo(State.UNREACHABLE);
    }

    @Test
    void hostState_lanServiceInsideServerLanCidr_returnsOk() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "box.example.com", "172.31.5.20", 8080, "http", "svc");

        assertThat(route.hostState(List.of(), List.of(), List.of(), "172.31.0.0/16")).isEqualTo(State.OK);
    }

    @Test
    void hostState_lanServiceNeitherRelayNorServerLanCidr_returnsUnreachable() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute("r", "box.example.com", "10.99.99.99", 8080, "http", "svc");

        assertThat(route.hostState(List.of(), List.of(), List.of(), "172.31.0.0/16")).isEqualTo(State.UNREACHABLE);
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
    void displayName_lanService_usesRelayPeerNameAsServer() {
        ReverseProxyRoute route = ReverseProxyRoute.lanRoute(
            "nut-router", "nut.apalveien5.example.com", "192.168.3.3", 3001, "http", "nut-svc");
        PeerConfiguration relay = new PeerConfiguration("apalveien5", "10.13.13.5", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.5");
        ForResolvingPeerNames resolver = ip -> ip;

        String name = route.displayName("example.com", List.of(), List.of(), resolver, List.of(relay));

        assertThat(name).isEqualTo("nut @ apalveien5");
    }

    // --- helpers ---

    private static ReverseProxyRoute route(String domain, String address, int port) {
        return new ReverseProxyRoute("route", domain, address, port, "svc", null);
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
