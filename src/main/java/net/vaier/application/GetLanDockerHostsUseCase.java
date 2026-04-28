package net.vaier.application;

import net.vaier.domain.LanDockerHost;

import java.util.List;

public interface GetLanDockerHostsUseCase {

    List<LanDockerHostView> getAll();

    /**
     * A registered LAN Docker host together with the resolved relay peer that routes
     * to it. {@code relayPeerName} is null when no current relay peer's lanCidr contains
     * the host's IP — typically because the relay was deleted or its lanCidr changed.
     */
    record LanDockerHostView(LanDockerHost host, String relayPeerName) {}
}
