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

    // --- displayName ---

    @Test
    void displayName_localService_returnsSubdomainAtLocal() {
        ReverseProxyRoute route = route("pihole.example.com", "pihole", 8080);
        ForResolvingPeerNames resolver = ip -> ip;

        String name = route.displayName("example.com", List.of(), List.of(), resolver);

        assertThat(name).isEqualTo("pihole @ local");
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
    void displayName_unknownAddress_fallsBackToLocal() {
        ReverseProxyRoute route = route("app.example.com", "10.13.13.5", 8080);
        ForResolvingPeerNames resolver = ip -> ip;

        String name = route.displayName("example.com", List.of(), List.of(), resolver);

        assertThat(name).isEqualTo("app @ local");
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
