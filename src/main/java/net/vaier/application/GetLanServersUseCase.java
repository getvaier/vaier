package net.vaier.application;

import net.vaier.domain.LanServer;

import java.util.List;

public interface GetLanServersUseCase {

    List<LanServerView> getAll();

    /**
     * A registered LAN server together with the resolved relay peer that routes
     * to it. {@code relayPeerName} is null when no current relay peer's lanCidr contains
     * the server's lanAddress — typically because the relay was deleted or its lanCidr changed.
     */
    record LanServerView(LanServer server, String relayPeerName) {}
}
