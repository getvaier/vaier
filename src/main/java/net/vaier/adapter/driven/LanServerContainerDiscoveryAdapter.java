package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForDiscoveringLanServerContainers;
import net.vaier.domain.port.ForGettingLanServers;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingServerInfo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Driven adapter that scrapes containers off a registered LAN server's Docker socket, hopping through
 * the relay peer's tunnel (or straight from the Vaier container when the address is in the server's own
 * subnet). This is genuinely infrastructure — a live Docker/SSH scrape — that used to sit on
 * {@code ContainerService}; a {@code *Service} must not implement a driven ({@code For*}) port, so it
 * moved here. Reads the LAN-server catalogue via {@link ForGettingLanServers} and the Docker socket via
 * {@link ForGettingServerInfo}.
 */
@Component
@Slf4j
public class LanServerContainerDiscoveryAdapter implements ForDiscoveringLanServerContainers {

    private final ForGettingLanServers forGettingLanServers;
    private final ForGettingServerInfo forGettingServerInfo;

    public LanServerContainerDiscoveryAdapter(ForGettingLanServers forGettingLanServers,
                                              ForGettingServerInfo forGettingServerInfo) {
        this.forGettingLanServers = forGettingLanServers;
        this.forGettingServerInfo = forGettingServerInfo;
    }

    @Override
    public List<LanServerContainers> discoverAllLanServerContainers() {
        return forGettingLanServers.getAll().stream()
            .filter(view -> view.server().runsDocker())
            .map(this::scrapeLanServer)
            .toList();
    }

    @Override
    public LanServerContainers discoverLanServerContainersForHost(String name) {
        LanServerView view = forGettingLanServers.getAll().stream()
            .filter(v -> v.server().name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("LAN server not found: " + name));
        if (!view.server().runsDocker()) {
            throw new IllegalArgumentException(
                "LAN server " + name + " does not run Docker");
        }
        return scrapeLanServer(view);
    }

    private LanServerContainers scrapeLanServer(LanServerView view) {
        var server = view.server();
        if (view.relayPeerName() == null) {
            log.debug("Skipping LAN server {} ({}) — not inside any relay peer's lanCidr nor the server LAN CIDR",
                server.name(), server.lanAddress());
            return new LanServerContainers(server.name(), server.lanAddress(), server.dockerPort(),
                null, "UNREACHABLE", List.of());
        }
        // relayPeerName is either a relay peer (scrape hops through its tunnel + LAN forwarding)
        // or LanAnchor.VAIER_SERVER_NAME (scrape goes straight from the Vaier container, since the
        // address is in the Vaier server's own subnet). The Docker socket target is the same.
        try {
            Server target = new Server(server.lanAddress(), server.dockerPort(), false);
            List<DockerService> containers = forGettingServerInfo.getServicesWithExposedPorts(target);
            log.info("Discovered {} containers on LAN server {} ({}) via {}",
                containers.size(), server.name(), server.lanAddress(), view.relayPeerName());
            return new LanServerContainers(server.name(), server.lanAddress(), server.dockerPort(),
                view.relayPeerName(), "OK", containers);
        } catch (Exception e) {
            log.warn("Failed to query Docker on LAN server {} ({}): {}",
                server.name(), server.lanAddress(), e.getMessage());
            return new LanServerContainers(server.name(), server.lanAddress(), server.dockerPort(),
                view.relayPeerName(), "UNREACHABLE", List.of());
        }
    }
}
