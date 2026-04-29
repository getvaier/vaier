package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DiscoverLanServerContainersUseCase;
import net.vaier.application.DiscoverLocalContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.GetLanServersUseCase.LanServerView;
import net.vaier.application.GetLocalDockerServicesUseCase;
import net.vaier.application.GetServerInfoUseCase;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.application.PublishPeerServiceUseCase.PublishableSource;
import net.vaier.config.ServiceNames;
import net.vaier.domain.DockerService;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.MachineType;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Server;
import net.vaier.domain.VpnClient;
import net.vaier.domain.WireguardClientImage;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class ContainerService implements
    DiscoverLocalContainersUseCase,
    DiscoverPeerContainersUseCase,
    DiscoverLanServerContainersUseCase,
    GetServerInfoUseCase,
    GetLocalDockerServicesUseCase {

    private static final Set<String> EXCLUDED_NAMES = Set.of(
        ServiceNames.WIREGUARD, ServiceNames.WIREGUARD_MASQUERADE,
        ServiceNames.AUTHELIA, ServiceNames.REDIS, ServiceNames.VAIER
    );

    private record KnownService(Set<Integer> allowedPorts, String rootRedirectPath) {}

    private static final Map<String, KnownService> KNOWN_SERVICES = Map.of(
        ServiceNames.TRAEFIK, new KnownService(Set.of(8080), "/dashboard/")
    );

    private final ForGettingServerInfo forGettingServerInfo;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForResolvingPeerNames forResolvingPeerNames;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final GetLanServersUseCase getLanServersUseCase;
    private final String vaierNetworkName;
    private final String dockerGatewayIp;

    @Autowired
    public ContainerService(ForGettingServerInfo forGettingServerInfo,
                            ForGettingVpnClients forGettingVpnClients,
                            ForResolvingPeerNames forResolvingPeerNames,
                            ForGettingPeerConfigurations forGettingPeerConfigurations,
                            GetLanServersUseCase getLanServersUseCase) {
        this(forGettingServerInfo, forGettingVpnClients, forResolvingPeerNames, forGettingPeerConfigurations,
            getLanServersUseCase,
            System.getenv().getOrDefault("VAIER_NETWORK_NAME", "vaier-network"),
            System.getenv().getOrDefault("VAIER_DOCKER_GATEWAY", "172.20.0.1"));
    }

    ContainerService(ForGettingServerInfo forGettingServerInfo,
                     ForGettingVpnClients forGettingVpnClients,
                     ForResolvingPeerNames forResolvingPeerNames,
                     ForGettingPeerConfigurations forGettingPeerConfigurations,
                     GetLanServersUseCase getLanServersUseCase,
                     String vaierNetworkName,
                     String dockerGatewayIp) {
        this.forGettingServerInfo = forGettingServerInfo;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerNames = forResolvingPeerNames;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.getLanServersUseCase = getLanServersUseCase;
        this.vaierNetworkName = vaierNetworkName;
        this.dockerGatewayIp = dockerGatewayIp;
    }

    @Override
    public List<DockerService> discover() {
        return forGettingServerInfo.getServicesWithExposedPorts(Server.local());
    }

    @Override
    public List<DockerService> getServicesWithExposedPorts(Server server) {
        return forGettingServerInfo.getServicesWithExposedPorts(server);
    }

    @Override
    public List<PeerContainers> discoverAll() {
        List<VpnClient> clients = forGettingVpnClients.getClients();
        List<PeerContainers> results = new ArrayList<>();

        for (VpnClient client : clients) {
            String vpnIp = client.allowedIps().split("/")[0];
            String peerName = forResolvingPeerNames.resolvePeerNameByIp(vpnIp);

            MachineType peerType = forGettingPeerConfigurations.getPeerConfigByIp(vpnIp)
                    .map(ForGettingPeerConfigurations.PeerConfiguration::peerType)
                    .orElse(MachineType.UBUNTU_SERVER);

            if (!peerType.isVpnPeer() || !peerType.isServerType()) {
                log.debug("Skipping Docker discovery for non-server peer {} ({}) of type {}", peerName, vpnIp, peerType);
                continue;
            }

            if (!isPeerConnected(client)) {
                log.debug("Skipping Docker discovery for disconnected peer {} ({})", peerName, vpnIp);
                results.add(new PeerContainers(peerName, vpnIp, "UNREACHABLE", List.of(), false, WireguardClientImage.EXPECTED));
                continue;
            }

            try {
                Server server = new Server(vpnIp, 2375, false);
                List<DockerService> containers = forGettingServerInfo.getServicesWithExposedPorts(server);
                log.info("Discovered {} containers on peer {} ({})", containers.size(), peerName, vpnIp);
                results.add(new PeerContainers(peerName, vpnIp, "OK", containers, isWireguardOutdated(containers), WireguardClientImage.EXPECTED));
            } catch (Exception e) {
                log.warn("Failed to query Docker on peer {} ({}): {}", peerName, vpnIp, e.getMessage());
                results.add(new PeerContainers(peerName, vpnIp, "UNREACHABLE", List.of(), false, WireguardClientImage.EXPECTED));
            }
        }

        return results;
    }

    @Override
    public List<LanServerContainers> discoverAllLanServerContainers() {
        return getLanServersUseCase.getAll().stream()
            .filter(view -> view.server().runsDocker())
            .map(this::scrapeLanServer)
            .toList();
    }

    @Override
    public LanServerContainers discoverLanServerContainersForHost(String name) {
        LanServerView view = getLanServersUseCase.getAll().stream()
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
            log.debug("Skipping LAN server {} ({}) — no relay peer covers its lanCidr",
                server.name(), server.lanAddress());
            return new LanServerContainers(server.name(), server.lanAddress(), server.dockerPort(),
                null, "UNREACHABLE", List.of());
        }
        try {
            Server target = new Server(server.lanAddress(), server.dockerPort(), false);
            List<DockerService> containers = forGettingServerInfo.getServicesWithExposedPorts(target);
            log.info("Discovered {} containers on LAN server {} ({}) via relay {}",
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

    @Override
    public List<PublishableService> getUnpublishedLocalServices(List<ReverseProxyRoute> existingRoutes) {
        List<PublishableService> result = new ArrayList<>();
        try {
            forGettingServerInfo.getServicesWithExposedPorts(Server.local()).forEach(container -> {
                String name = container.containerName().toLowerCase();
                if (EXCLUDED_NAMES.contains(name)) return;

                KnownService known = KNOWN_SERVICES.get(name);

                container.ports().stream()
                    .filter(p -> "tcp".equals(p.type()))
                    .filter(p -> known == null || known.allowedPorts().contains(p.privatePort()))
                    .forEach(p -> {
                        ServiceEndpoint ep = resolveEndpoint(container, p);
                        if (ep == null) return;
                        if (existingRoutes.stream().anyMatch(r -> r.getAddress().equals(ep.address()) && r.getPort() == ep.port())) return;
                        result.add(new PublishableService(
                            PublishableSource.LOCAL,
                            null,
                            ep.address(),
                            container.containerName(),
                            ep.port(),
                            known != null ? known.rootRedirectPath() : null,
                            false
                        ));
                    });
            });
        } catch (Exception e) {
            log.warn("Failed to query local Docker socket: {}", e.getMessage());
        }
        return result;
    }

    private boolean isWireguardOutdated(List<DockerService> containers) {
        return containers.stream()
                .map(DockerService::image)
                .filter(WireguardClientImage::isWireguardImage)
                .anyMatch(image -> !WireguardClientImage.matchesExpected(image));
    }

    private boolean isPeerConnected(VpnClient peer) {
        try {
            long handshake = Long.parseLong(peer.latestHandshake());
            long now = System.currentTimeMillis() / 1000;
            return handshake > 0 && (now - handshake) < 300;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private ServiceEndpoint resolveEndpoint(DockerService container, PortMapping p) {
        boolean onVaierNetwork = container.networks().isEmpty() || container.networks().contains(vaierNetworkName);
        if (onVaierNetwork) {
            return new ServiceEndpoint(container.containerName(), p.privatePort());
        }
        if (p.publicPort() != null) {
            return new ServiceEndpoint(dockerGatewayIp, p.publicPort());
        }
        return null;
    }

    private record ServiceEndpoint(String address, int port) {}
}
