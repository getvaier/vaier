package net.vaier.domain.port;

import net.vaier.domain.MachineType;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PeerConfigurationTest {

    private static PeerConfiguration peer(String id, String lanCidr) {
        return new PeerConfiguration(id, id, "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, lanCidr, null, null);
    }

    @Test
    void lanCidrOwner_findsThePeerThatAlreadyOwnsTheCidr() {
        List<PeerConfiguration> peers = List.of(
            peer("alice", "192.168.1.0/24"),
            peer("bob", "192.168.2.0/24"));

        assertThat(PeerConfiguration.lanCidrOwner(peers, "192.168.2.0/24", "alice"))
            .map(PeerConfiguration::id)
            .contains("bob");
    }

    @Test
    void lanCidrOwner_ignoresThePeerBeingExcluded() {
        List<PeerConfiguration> peers = List.of(peer("alice", "192.168.1.0/24"));

        assertThat(PeerConfiguration.lanCidrOwner(peers, "192.168.1.0/24", "alice")).isEmpty();
    }

    @Test
    void lanCidrOwner_emptyWhenNoPeerOwnsTheCidr() {
        List<PeerConfiguration> peers = List.of(peer("alice", "192.168.1.0/24"));

        assertThat(PeerConfiguration.lanCidrOwner(peers, "192.168.9.0/24", "bob")).isEmpty();
        assertThat(PeerConfiguration.lanCidrOwner(peers, null, "bob")).isEmpty();
    }
}
