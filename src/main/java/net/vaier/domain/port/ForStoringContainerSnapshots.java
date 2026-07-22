package net.vaier.domain.port;

import net.vaier.domain.DockerService;
import net.vaier.domain.ScopedImage;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;

import java.util.List;
import java.util.Map;

/**
 * Driven port for the write/owner side of the cached container snapshots. The peer- and
 * Vaier-server container scrapes and the last image-update sweep's verdicts used to live as
 * {@code volatile} fields on {@code ContainerService}, exposed through driven read ports the service
 * itself implemented — which a {@code *Service} must not do. The state moved to a store adapter; the
 * scrape/sweep use cases (which stay in {@code ContainerService}) write it and read it raw through
 * this port, while consumers read the decorated views through {@link ForDiscoveringVaierServerContainers},
 * {@link ForDiscoveringPeerContainers} and {@link ForGettingVaierServerDockerServices}.
 */
public interface ForStoringContainerSnapshots {

    /** Replace the cached server-peer container scrape. */
    void storePeerContainers(List<PeerContainers> peers);

    /** Replace the cached Vaier-server container scrape. */
    void storeVaierServerContainers(List<DockerService> containers);

    /** Replace the last image-update sweep's verdicts (image → verdict). */
    void storeImageUpdateVerdicts(Map<ScopedImage, UpdateAvailability> verdicts);

    /** The raw cached server-peer scrape (undecorated), for feeding a sweep. */
    List<PeerContainers> peerContainers();

    /** The raw cached Vaier-server scrape (undecorated), for feeding a sweep. */
    List<DockerService> vaierServerContainers();

    /** The last sweep's verdicts, for comparing a fresh sweep against. */
    Map<ScopedImage, UpdateAvailability> imageUpdateVerdicts();
}
