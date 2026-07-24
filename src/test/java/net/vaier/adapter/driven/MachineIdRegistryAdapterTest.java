package net.vaier.adapter.driven;

import net.vaier.domain.LanServer;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MachineIdRegistryAdapterTest {

    private static final MachineId PEER_ID = MachineId.of("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private static final MachineId LAN_ID = MachineId.of("11111111-2222-3333-4444-555555555555");
    private static final MachineId SERVER_ID = MachineId.of("99999999-8888-7777-6666-555555555555");

    private ForGettingPeerConfigurations peers;
    private ForPersistingLanServers lanServers;
    private ForPersistingAppConfiguration appConfig;
    private MachineIdRegistryAdapter adapter;

    @BeforeEach
    void setUp() {
        peers = mock(ForGettingPeerConfigurations.class);
        lanServers = mock(ForPersistingLanServers.class);
        appConfig = mock(ForPersistingAppConfiguration.class);
        adapter = new MachineIdRegistryAdapter(peers, lanServers, appConfig);

        when(peers.getAllPeerConfigs()).thenReturn(List.of(new PeerConfiguration(
            "apalveien5", "Apalveien 5", "10.13.13.6", "", MachineType.UBUNTU_SERVER,
            null, null, null, null, null, PEER_ID)));
        when(lanServers.getAll()).thenReturn(List.of(
            new LanServer("NAS", "192.168.3.3", false, null, null, null, null, LAN_ID)));
        when(appConfig.load()).thenReturn(Optional.of(
            VaierConfig.builder().vaierServerMachineId(SERVER_ID.value()).build()));
    }

    @Test
    void idForName_resolvesAPeerByItsDisplayName() {
        assertThat(adapter.idForName("Apalveien 5")).contains(PEER_ID);
    }

    @Test
    void idForName_resolvesALanServer() {
        assertThat(adapter.idForName("NAS")).contains(LAN_ID);
    }

    @Test
    void idForName_resolvesTheVaierServerFromItsConfiguredId() {
        assertThat(adapter.idForName("Vaier server")).contains(SERVER_ID);
    }

    /**
     * The same comparison rule that makes names safe to look up at all — the uniqueness guard rejects
     * "nas" when "NAS" exists, so a lookup must find it too. The two disagreeing is how a machine ends
     * up unreachable by the very name the UI shows for it.
     */
    @Test
    void idForName_ignoresCaseAndSurroundingWhitespace() {
        assertThat(adapter.idForName("  nas  ")).contains(LAN_ID);
    }

    @Test
    void idForName_isEmptyWhenNoMachineBearsTheName() {
        assertThat(adapter.idForName("nope")).isEmpty();
    }

    @Test
    void idForName_isEmptyForNullOrBlank() {
        assertThat(adapter.idForName(null)).isEmpty();
        assertThat(adapter.idForName("   ")).isEmpty();
    }

    @Test
    void idForName_isEmptyForTheVaierServerWhenItHasNoConfiguredId() {
        when(appConfig.load()).thenReturn(Optional.of(VaierConfig.builder().build()));

        assertThat(adapter.idForName("Vaier server")).isEmpty();
    }
}
