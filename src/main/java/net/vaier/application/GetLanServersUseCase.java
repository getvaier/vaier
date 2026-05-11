package net.vaier.application;

import net.vaier.domain.LanServer;

import java.util.List;

public interface GetLanServersUseCase {

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
