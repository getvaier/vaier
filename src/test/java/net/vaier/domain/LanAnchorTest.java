package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LanAnchorTest {

    private static PeerConfiguration relay(String name, String ip, String lanCidr) {
        return new PeerConfiguration(name, ip, "", MachineType.UBUNTU_SERVER, lanCidr, null);
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
}
