package net.vaier.domain.port;

import net.vaier.domain.DockerService;
import net.vaier.domain.ScopedImage;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Replace the set of image strings that are <b>moving tags</b>, so the Explorer can say why an image
     * wears the <b>update available</b> mark and yet raises no alert. By image STRING: a registry answers
     * about a tag, not about a host.
     */
    void storeMovingTags(Set<String> images);

    /**
     * Forget the remembered verdict for one {@link ScopedImage}, so it reads
     * {@link UpdateAvailability#UNKNOWN} again — what every image no sweep has judged reads.
     *
     * <p>Deliberately narrow. The update path needs to retire exactly one container's verdict, and doing
     * that by rewriting the whole map would race the sweep: a sweep landing between an update's pull and
     * its settle would be clobbered by a map assembled before it ran. One key, left to the store to remove.
     */
    void forgetImageUpdateVerdict(ScopedImage image);

    /** Whether any peer scrape has been stored yet — an empty cache before the first is not "no containers". */
    boolean peerContainersStored();

    /** The raw cached server-peer scrape (undecorated), for feeding a sweep. */
    List<PeerContainers> peerContainers();

    /** The raw cached Vaier-server scrape (undecorated), for feeding a sweep. */
    List<DockerService> vaierServerContainers();

    /** The last sweep's verdicts, for comparing a fresh sweep against. */
    Map<ScopedImage, UpdateAvailability> imageUpdateVerdicts();
}
