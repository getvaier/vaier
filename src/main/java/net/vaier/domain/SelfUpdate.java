package net.vaier.domain;

import net.vaier.domain.port.ForResolvingRegistryDigest;

import java.util.List;
import java.util.Optional;

/**
 * Vaier updating itself: which of the fleet's containers is Vaier, and whether there is anything to do.
 *
 * <p><b>Why this is allowed at all.</b> {@code ImageUpdateWatcher} states the standing rule — Vaier never
 * pulls; detection is read-only and the operator's move is the operator's. That rule is about reaching into
 * someone else's machine and restarting their service, which Vaier has no business doing on a hunch. Doing it
 * to <em>yourself</em>, when a person has pressed a button asking for exactly that, is a different act. So
 * this is the one image Vaier may replace, and the identification below exists to make sure it is really the
 * only one.
 */
public final class SelfUpdate {

    /** The image repository Vaier's own container runs, whatever tag or digest is pinned to it. */
    public static final String IMAGE_REPOSITORY = "getvaier/vaier";

    private SelfUpdate() {}

    /**
     * Vaier's own container among a machine's containers, identified by <b>image repository</b> rather than
     * by container name. Two reasons, and both have bitten this project's neighbours: a compose project takes
     * its name from its directory, so the container is only called {@code vaier} by convention; and the stack
     * runs a second container whose name begins with "vaier" — {@code vaier-offline}, which exists precisely
     * to stay up while Vaier is down. Matching on the name would let an update recreate the one container
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
     * Whether there is a newer Vaier image to move to — asked of the registry <b>here</b>, rather than read
     * off the container's {@code updateAvailable} verdict.
     *
     * <p><b>Why it asks for itself (#353).</b> The update sweep no longer judges Vaier's own stack at all,
     * {@code vaier} included: those images move with a Vaier release, so a mark on one is an alert whose only
     * resolution is "wait for a release" — and on a host that builds Vaier locally the mark is worse than
     * useless, since the local digest differs from what Hub serves for {@code latest} and acting on it would
     * <b>downgrade</b>. Settings has a real button, so it asks the one question it actually needs about the
     * one image it may act on. Reading the sweep's verdict here after #353 would leave Settings permanently
     * blind, which would turn that fix into a regression on the path it was justified by.
     *
     * <p>Only a genuine difference counts. An empty answer — registry unreachable, rate-limited, or a locally
     * built image with no digest to compare — is {@link UpdateAvailability#UNKNOWN}, and "cannot tell" is
     * never a reason to recreate the container the fleet's whole control plane runs inside: that would turn a
     * rate limit into an outage. A registry that throws is read exactly as one that could not answer.
     */
    public static boolean updateAvailable(List<DockerService> containers,
                                          ForResolvingRegistryDigest registry) {
        return findSelf(containers)
            .map(self -> UpdateAvailability.compare(self.imageDigests(), servedDigest(self, registry))
                == UpdateAvailability.UPDATE_AVAILABLE)
            .orElse(false);
    }

    /** What the registry serves for Vaier's own image right now, or null when it could not say. */
    private static String servedDigest(DockerService self, ForResolvingRegistryDigest registry) {
        try {
            return ImageReference.parse(self.image())
                .flatMap(registry::resolveDigest)
                .orElse(null);
        } catch (Exception e) {
            // Unreachable, rate-limited, unauthorized: cannot tell, which is not an update.
            return null;
        }
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
