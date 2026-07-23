package net.vaier.domain;

import java.util.List;
import java.util.Optional;

/**
 * Vaier upgrading itself: which of the fleet's containers is Vaier, and whether there is anything to do.
 *
 * <p><b>Why this is allowed at all.</b> {@code ImageUpdateWatcher} states the standing rule — Vaier never
 * pulls; detection is read-only and the operator's move is the operator's. That rule is about reaching into
 * someone else's machine and restarting their service, which Vaier has no business doing on a hunch. Doing it
 * to <em>yourself</em>, when a person has pressed a button asking for exactly that, is a different act. So
 * this is the one image Vaier may replace, and the identification below exists to make sure it is really the
 * only one.
 */
public final class SelfUpgrade {

    /** The image repository Vaier's own container runs, whatever tag or digest is pinned to it. */
    public static final String IMAGE_REPOSITORY = "getvaier/vaier";

    private SelfUpgrade() {}

    /**
     * Vaier's own container among a machine's containers, identified by <b>image repository</b> rather than
     * by container name. Two reasons, and both have bitten this project's neighbours: a compose project takes
     * its name from its directory, so the container is only called {@code vaier} by convention; and the stack
     * runs a second container whose name begins with "vaier" — {@code vaier-offline}, which exists precisely
     * to stay up while Vaier is down. Matching on the name would let an upgrade recreate the one container
     * that must survive it.
     */
    public static Optional<DockerService> findSelf(List<DockerService> containers) {
        if (containers == null) {
            return Optional.empty();
        }
        return containers.stream()
            .filter(c -> c.image() != null && IMAGE_REPOSITORY.equals(repositoryOf(c.image())))
            .findFirst();
    }

    /**
     * Whether there is a newer Vaier image to move to. Only {@link UpdateAvailability#UPDATE_AVAILABLE}
     * counts: {@code UNKNOWN} means the registry could not be reached, was rate-limited, or the image was
     * built locally with no registry digest — none of which is a reason to recreate the container the fleet's
     * whole control plane is running inside. Treating "cannot tell" as "upgrade" would turn a rate limit into
     * an outage.
     */
    public static boolean upgradeAvailable(List<DockerService> containers) {
        return findSelf(containers)
            .map(c -> c.updateAvailable() == UpdateAvailability.UPDATE_AVAILABLE)
            .orElse(false);
    }

    /**
     * The repository part of an image reference — {@code getvaier/vaier} from {@code getvaier/vaier:latest}
     * or {@code getvaier/vaier@sha256:…}. The tag separator is only a tag separator when it comes after the
     * last slash; before it, it is a registry port ({@code registry:5000/vaier}).
     */
    private static String repositoryOf(String image) {
        int at = image.indexOf('@');
        String withoutDigest = at < 0 ? image : image.substring(0, at);
        int colon = withoutDigest.lastIndexOf(':');
        int slash = withoutDigest.lastIndexOf('/');
        return colon > slash ? withoutDigest.substring(0, colon) : withoutDigest;
    }
}
