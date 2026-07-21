package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class LanAnchorTest {

    private static PeerConfiguration relay(String name, String ip, String lanCidr) {
        return new PeerConfiguration(name, ip, "", MachineType.UBUNTU_SERVER, lanCidr, null);
    }

    /** A peer whose id and display name differ — so we can tell {@code anchorKey()} from {@code name()}. */
    private static PeerConfiguration namedRelay(String id, String displayName, String ip, String lanCidr) {
        return new PeerConfiguration(id, displayName, ip, "", MachineType.UBUNTU_SERVER, lanCidr, null, null);
    }

    @Test
    void resolvesRelayPeerWhoseLanCidrContainsTheAddress() {
        var anchor = LanAnchor.resolve("192.168.3.50", List.of(relay("apalveien5", "10.13.13.5", "192.168.3.0/24")), null);

        assertThat(anchor).isPresent();
        assertThat(anchor.get().isVaierServer()).isFalse();
        assertThat(anchor.get().name()).isEqualTo("apalveien5");
        assertThat(anchor.get().cidr()).isEqualTo("192.168.3.0/24");
        assertThat(anchor.get().relayPeer()).map(PeerConfiguration::ipAddress).contains("10.13.13.5");
    }

    @Test
    void resolvesVaierServerWhenAddressIsInServerLanCidrAndNoRelayMatches() {
        var anchor = LanAnchor.resolve("172.31.5.20", List.of(), "172.31.0.0/16");

        assertThat(anchor).isPresent();
        assertThat(anchor.get().isVaierServer()).isTrue();
        assertThat(anchor.get().name()).isEqualTo("Vaier server");
        assertThat(anchor.get().cidr()).isEqualTo("172.31.0.0/16");
        assertThat(anchor.get().relayPeer()).isEmpty();
    }

    @Test
    void relayPeerWinsWhenAddressIsCoveredByBothARelayAndTheServerLanCidr() {
        var anchor = LanAnchor.resolve("192.168.3.50",
            List.of(relay("apalveien5", "10.13.13.5", "192.168.3.0/24")), "192.168.0.0/16");

        assertThat(anchor).isPresent();
        assertThat(anchor.get().isVaierServer()).isFalse();
        assertThat(anchor.get().name()).isEqualTo("apalveien5");
    }

    @Test
    void emptyWhenNoRelayAndNoServerLanCidrCoverTheAddress() {
        assertThat(LanAnchor.resolve("10.99.99.99", List.of(relay("r", "10.13.13.5", "192.168.3.0/24")), "172.31.0.0/16"))
            .isEmpty();
    }

    @Test
    void nullOrBlankServerLanCidrIsIgnored() {
        assertThat(LanAnchor.resolve("172.31.5.20", List.of(), null)).isEmpty();
        assertThat(LanAnchor.resolve("172.31.5.20", List.of(), "  ")).isEmpty();
    }

    @Test
    void malformedRelayLanCidrIsSkipped() {
        var anchor = LanAnchor.resolve("172.31.5.20",
            List.of(relay("broken", "10.13.13.5", "not-a-cidr")), "172.31.0.0/16");

        assertThat(anchor).isPresent();
        assertThat(anchor.get().isVaierServer()).isTrue();
    }

    // --- scannable LANs & anchor-key identity (targeted single-LAN scan) ---

    @Test
    void anchorKeyIsThePeerIdForARelayAndTheServerNameForTheServer() {
        var relay = LanAnchor.resolve("192.168.3.50",
            List.of(namedRelay("apalveien5", "Apalveien 5", "10.13.13.5", "192.168.3.0/24")), null).orElseThrow();
        assertThat(relay.anchorKey()).isEqualTo("apalveien5");   // the routing key, not the display name
        assertThat(relay.name()).isEqualTo("Apalveien 5");

        var server = LanAnchor.resolve("172.31.5.20", List.of(), "172.31.0.0/16").orElseThrow();
        assertThat(server.anchorKey()).isEqualTo(LanAnchor.VAIER_SERVER_NAME);
    }

    @Test
    void scannableListsEveryRelayWithACidrThenTheServerLan() {
        var peers = List.of(
            namedRelay("apalveien5", "Apalveien 5", "10.13.13.5", "192.168.3.0/24"),
            relay("nolan", "10.13.13.6", null),                       // no LAN — not scannable
            namedRelay("colina27", "Colina 27", "10.13.13.3", "192.168.1.0/24"));

        var scannable = LanAnchor.scannable(peers, Optional.of("172.31.0.0/16"));

        assertThat(scannable)
            .extracting(LanAnchor::anchorKey, LanAnchor::name, LanAnchor::cidr)
            .containsExactly(
                tuple("apalveien5", "Apalveien 5", "192.168.3.0/24"),
                tuple("colina27", "Colina 27", "192.168.1.0/24"),
                tuple(LanAnchor.VAIER_SERVER_NAME, LanAnchor.VAIER_SERVER_NAME, "172.31.0.0/16"));
    }

    @Test
    void scannableOmitsTheServerLanWhenNoServerCidrIsKnown() {
        var scannable = LanAnchor.scannable(
            List.of(namedRelay("apalveien5", "Apalveien 5", "10.13.13.5", "192.168.3.0/24")), Optional.empty());

        assertThat(scannable).extracting(LanAnchor::anchorKey).containsExactly("apalveien5");
    }

    @Test
    void byKeyResolvesARelayByItsIdToItsCidr() {
        var peers = List.of(namedRelay("apalveien5", "Apalveien 5", "10.13.13.5", "192.168.3.0/24"));

        var anchor = LanAnchor.byKey("apalveien5", peers, Optional.of("172.31.0.0/16"));

        assertThat(anchor).isPresent();
        assertThat(anchor.get().isVaierServer()).isFalse();
        assertThat(anchor.get().cidr()).isEqualTo("192.168.3.0/24");
    }

    @Test
    void byKeyResolvesTheServerLanByItsCanonicalName() {
        var anchor = LanAnchor.byKey(LanAnchor.VAIER_SERVER_NAME, List.of(), Optional.of("172.31.0.0/16"));

        assertThat(anchor).isPresent();
        assertThat(anchor.get().isVaierServer()).isTrue();
        assertThat(anchor.get().cidr()).isEqualTo("172.31.0.0/16");
    }

    @Test
    void byKeyIsEmptyForTheServerNameWhenNoServerCidrIsKnown() {
        assertThat(LanAnchor.byKey(LanAnchor.VAIER_SERVER_NAME, List.of(), Optional.empty())).isEmpty();
    }

    @Test
    void byKeyIsEmptyForARelayWithNoLanCidrAndForAnUnknownKey() {
        var peers = List.of(relay("nolan", "10.13.13.6", null));

        assertThat(LanAnchor.byKey("nolan", peers, Optional.empty())).isEmpty();
        assertThat(LanAnchor.byKey("ghost", peers, Optional.empty())).isEmpty();
    }
}
