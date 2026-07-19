package net.vaier.domain;

/**
 * One container image as it runs <b>on a particular machine</b> — the unit Vaier tracks for
 * <b>update available</b> (#57). The refinement that made the alert actionable: the same tag may run on the
 * Vaier server and on two peers at once, and an operator told only "vaultwarden/server:latest has an update"
 * cannot tell which host to SSH into. Scoping the verdict to a machine answers that.
 *
 * <p>It is deliberately not the unit a <b>registry</b> is asked about — that is the image string, and asking
 * once per (machine, image) would multiply the rate-limited manifest requests by the fleet size for no new
 * information, since the same tag resolves to the same digest everywhere. The machine granularity lives only
 * in the <em>verdict</em>: each container's own local <b>image digest</b> is compared against that one shared
 * <b>registry digest</b>, so the same tag can read out of date on one machine and up to date on another.
 *
 * @param machine the machine the image runs on — {@code LanAnchor.VAIER_SERVER_NAME} for the Vaier server's
 *                own containers, or a server peer's name
 * @param image   the image string as the host reports it
 */
public record ScopedImage(String machine, String image) {

    /** How this reads to an operator, e.g. {@code "vaultwarden/server:latest on Apalveien 5"}. */
    public String label() {
        return image + " on " + machine;
    }
}
