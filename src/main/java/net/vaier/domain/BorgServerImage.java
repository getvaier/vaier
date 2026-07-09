package net.vaier.domain;

/**
 * The single pinned borg-server image Vaier stands up when it provisions a {@link BackupServer}, mirroring
 * {@link WireguardClientImage}: one canonical constant so the generated {@code docker-compose.yml} and any
 * drift check reference the same tag.
 *
 * <p>The pin is deliberate and load-bearing. {@value #EXPECTED} ships <strong>borg 1.4.3</strong>; the
 * upstream {@code 3.0.0-borg2.*} line ships borg 2.0.0 <em>beta</em>, whose repository format is
 * incompatible with the fleet's borg-1.x clients (e.g. Colina at 1.2.8). The floating {@code :latest} tag
 * is happens-to-be borg 1.x today but is unpinned and will roll to borg 2 — so it must never be used.
 */
public final class BorgServerImage {

    /** The pinned borg-server image (borg 1.4.3). Do not float this to {@code :latest} or the borg-2 line. */
    public static final String EXPECTED = "horaceworblehat/borg-server:2.8.6";

    /**
     * The borg version the {@link #EXPECTED} tag ships (verified by running the image). It is derived, not
     * probed: a Vaier-managed server's client key is a <em>restricted, forced-command</em> key
     * ({@code command="borg serve …"}), so an SSH session can never run {@code borg --version} to ask — the
     * forced command discards it. Because we stand the server up ourselves, we know exactly what it runs.
     *
     * <p><strong>Keep this in lock-step with {@link #EXPECTED}.</strong> Whenever the pin moves, re-verify
     * the borg version the new tag ships and update this constant in the same change — a pin-drift guard test
     * ({@code BorgServerImageTest}) fails if the tag changes without it.
     */
    public static final BorgVersion BORG_VERSION = new BorgVersion(1, 4, 3);

    private BorgServerImage() {}

    /** The borg version a Vaier-managed server runs, derived from the {@link #EXPECTED} pin (see {@link #BORG_VERSION}). */
    public static BorgVersion borgVersion() {
        return BORG_VERSION;
    }

    /** Whether the {@link #EXPECTED} pin is a concrete, non-floating {@code repo:tag}. */
    public static boolean isPinned() {
        return !isFloatingTag(EXPECTED);
    }

    /**
     * Whether {@code image} is an unpinned/floating reference: null, untagged (no {@code :tag}), or the
     * moving {@code :latest} tag. A concrete {@code repo:tag} (like {@link #EXPECTED}) is not floating.
     */
    public static boolean isFloatingTag(String image) {
        if (image == null) {
            return true;
        }
        int colon = image.lastIndexOf(':');
        if (colon < 0 || colon == image.length() - 1) {
            return true; // no tag at all
        }
        return image.substring(colon + 1).equals("latest");
    }
}
