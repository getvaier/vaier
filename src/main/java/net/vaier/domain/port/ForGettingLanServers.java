package net.vaier.domain.port;

import net.vaier.domain.LanServer;

import java.util.List;

/**
 * Driven query port for reading registered LAN servers together with their resolved relay
 * anchor. Mirror of {@link ForPersistingLanServers}; used by other domains' services that need
 * a read-only view of the LAN-server catalogue without coupling to the inbound use case.
 */
public interface ForGettingLanServers {

    List<LanServerView> getAll();

    /**
     * A registered LAN server together with the name of whatever routes to it: a relay peer
     * whose {@code lanCidr} contains the server's {@code lanAddress}, or {@code "Vaier server"}
     * ({@link net.vaier.domain.LanAnchor#VAIER_SERVER_NAME}) when the address falls inside the
     * Vaier server's own LAN CIDR. {@code relayPeerName} is null when neither covers it —
     * typically because the relay was deleted or its lanCidr changed.
     */
    record LanServerView(LanServer server, String relayPeerName) {}
}
