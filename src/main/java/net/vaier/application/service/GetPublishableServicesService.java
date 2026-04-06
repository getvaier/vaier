package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.GetLocalDockerServicesUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.application.PublishPeerServiceUseCase.PublishableSource;
import net.vaier.domain.port.ForManagingIgnoredServices;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GetPublishableServicesService implements GetPublishableServicesUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final GetLocalDockerServicesUseCase getLocalDockerServicesUseCase;
    private final PendingPublicationsTracker pendingPublicationsTracker;
    private final ForManagingIgnoredServices forManagingIgnoredServices;

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
                    .filter(p -> !pendingPublicationsTracker.isPending(peer.vpnIp(), p.publicPort()))
                    .map(p -> new PublishableService(PublishableSource.PEER, peer.peerName(), peer.vpnIp(), container.containerName(), p.publicPort(), null, false))
                )
            )
            .forEach(publishable::add);

        getLocalDockerServicesUseCase.getUnpublishedLocalServices(existingRoutes).stream()
            .filter(s -> !pendingPublicationsTracker.isPending(s.address(), s.port()))
            .forEach(publishable::add);

        Set<String> ignoredKeys = forManagingIgnoredServices.getIgnoredServiceKeys();
        return publishable.stream().distinct()
            .map(s -> new PublishableService(s.source(), s.peerName(), s.address(), s.containerName(), s.port(), s.rootRedirectPath(), ignoredKeys.contains(ignoreKey(s))))
            .toList();
    }

    static String ignoreKey(PublishableService s) {
        return s.source() == PublishableSource.PEER
            ? s.peerName() + "/" + s.containerName() + ":" + s.port()
            : s.containerName() + ":" + s.port();
    }
}
