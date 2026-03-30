package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.GetLocalDockerServicesUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.application.PublishPeerServiceUseCase.PublishableSource;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GetPublishableServicesService implements GetPublishableServicesUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final GetLocalDockerServicesUseCase getLocalDockerServicesUseCase;

    @Override
    public List<PublishableService> getPublishableServices() {
        var existingRoutes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        var publishable = new ArrayList<PublishableService>();

        discoverPeerContainersUseCase.discoverAll().stream()
            .filter(peer -> "OK".equals(peer.status()))
            .flatMap(peer -> peer.containers().stream()
                .flatMap(container -> container.ports().stream()
                    .filter(p -> "tcp".equals(p.type()))
                    .filter(p -> existingRoutes.stream()
                        .noneMatch(r -> r.getAddress().equals(peer.vpnIp()) && r.getPort() == p.publicPort()))
                    .map(p -> new PublishableService(PublishableSource.PEER, peer.peerName(), peer.vpnIp(), container.containerName(), p.publicPort(), null))
                )
            )
            .forEach(publishable::add);

        publishable.addAll(getLocalDockerServicesUseCase.getUnpublishedLocalServices(existingRoutes));

        return publishable.stream().distinct().toList();
    }
}
