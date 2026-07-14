package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingVaierServerSshAddress;

import java.util.Optional;

/**
 * Where Vaier opens the SSH connection for a machine — a decision by <b>machine kind</b>, not a lookup:
 * a VPN peer answers at its <b>tunnel IP</b>, a LAN server at its <b>lanAddress</b>, and the Vaier server
 * host at the address it resolves to from inside the container (it is neither a peer nor a LAN server, so
 * its address cannot be read from config at all).
 *
 * <p>It lives in the domain because every consumer must get the same answer: the web terminal, remote
 * commands and the Explorer all reach the same machine at the same place. The stores it needs arrive as
 * driven ports, which the domain calls itself.
 */
public final class SshAddress {

    private SshAddress() {
    }

    /**
     * The SSH host for {@code machineName}. Throws {@link NotFoundException} when no machine bears the
     * name — being neither a peer, nor a LAN server, nor the Vaier server, it does not exist.
     */
    public static String of(String machineName,
                            ForGettingPeerConfigurations peers,
                            ForPersistingLanServers lanServers,
                            ForResolvingVaierServerSshAddress vaierServer) {
        if (LanAnchor.VAIER_SERVER_NAME.equals(machineName)) {
            return vaierServer.resolve();
        }
        Optional<PeerConfiguration> peer = peers.getAllPeerConfigs().stream()
            .filter(p -> machineName.equals(p.name()))
            .findFirst();
        if (peer.isPresent()) {
            return peer.get().ipAddress();
        }
        return LanServer.findByName(machineName, lanServers.getAll())
            .map(LanServer::lanAddress)
            .orElseThrow(() -> new NotFoundException("Machine not found: " + machineName));
    }
}
