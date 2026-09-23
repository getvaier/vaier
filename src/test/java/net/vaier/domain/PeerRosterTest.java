package net.vaier.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

class PeerRosterTest {

    private static VpnClient live(String ip) {
        return new VpnClient("live-key", ip + "/32", "1.2.3.4", "51820", "0", "0", "0");
    }

    private static PeerConfiguration configured(String id, String ip) {
        return new PeerConfiguration(id, ip, "[Interface]", MachineType.MOBILE_CLIENT, null, null);
    }

    @Test
    void aConfiguredPeerTheInterfaceHasForgotten_isListedAsAbsent() {
        // The live bug: a phone's directory outlived its entry on wg0, so the peers list never showed it,
        // the fleet page took it for a LAN server, and its delete went to the wrong door.
        List<VpnClient> roster = PeerRoster.reconcile(List.of(live("10.13.13.9")),
            List.of(configured("Ruten", "10.13.13.8"), configured("Ruten-2", "10.13.13.9")));

        assertThat(roster).hasSize(2);
        VpnClient absent = roster.get(1);
        assertThat(absent.vpnIp()).isEqualTo("10.13.13.8");
        assertThat(absent.isConnected()).isFalse();
        assertThat(absent.endpointIp()).isEmpty();
    }

    @Test
    void aLivePeer_isListedOnce_notAgainForItsConfig() {
        List<VpnClient> roster = PeerRoster.reconcile(List.of(live("10.13.13.9")),
            List.of(configured("Ruten-2", "10.13.13.9")));

        assertThat(roster).hasSize(1);
        assertThat(roster.get(0).publicKey()).isEqualTo("live-key");
    }

    @Test
    void aRelayCoveringALanCidr_isNotMistakenForAnAbsentLanPeer() {
        VpnClient relay = new VpnClient("relay", "10.13.13.3/32,192.168.1.0/24", "1.2.3.4", "51820", "0", "0", "0");

        List<VpnClient> roster = PeerRoster.reconcile(List.of(relay), List.of(configured("Colina-27", "10.13.13.3")));

        assertThat(roster).containsExactly(relay);
    }

    @Test
    void byAddress_keysEveryAddressedConfigOnce_theFirstWinsAndAnAddresslessOneIsLeftOut() {
        // The refresh builds each peer's view from the one read of every config; the rule for which configs
        // count, keyed by address, lives here beside reconcile rather than being restated by the service.
        Map<String, PeerConfiguration> byAddress = PeerRoster.byAddress(List.of(
            configured("Ruten", "10.13.13.8"), configured("Ruten-twin", "10.13.13.8"),
            configured("nameless", ""), configured("Geir-pc", "10.13.13.7")));

        assertThat(byAddress).containsOnlyKeys("10.13.13.8", "10.13.13.7");
        assertThat(byAddress.get("10.13.13.8").id()).isEqualTo("Ruten");
    }

    @Test
    void aConfigWithNoAddress_addsNothing() {
        assertThat(PeerRoster.reconcile(List.of(), List.of(configured("broken", " ")))).isEmpty();
    }
}
