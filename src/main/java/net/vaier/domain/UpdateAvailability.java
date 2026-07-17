package net.vaier.domain;

/**
 * What Vaier has decided about one container's image: it is current, a newer image is being served for the
 * very tag it runs, or Vaier <b>cannot tell</b>.
 *
 * <p>{@link #UNKNOWN} is the point of this type. Digest comparison fails in ordinary, non-alarming ways — the
 * registry is unreachable or rate-limited, the image was built locally and has no registry digest, the tag is
 * pinned to an immutable {@code @sha256:…}. None of those mean an update is available, and none of them mean
 * the container is current either. Collapsing "cannot tell" into either answer is how a monitor starts lying:
 * into {@code UP_TO_DATE} it hides a stale image (the #57 vaultwarden incident), into
 * {@code UPDATE_AVAILABLE} it cries wolf until admins filter the mail. So the third answer is a first-class
 * verdict, and it is what the operator is shown.
 */
public enum UpdateAvailability {

    /** Vaier could not resolve one of the two digests. Not outdated, not current — unknowable. */
    UNKNOWN,

    /** The registry serves the same digest this container already runs. */
    UP_TO_DATE,

    /** The registry serves a different digest for this container's tag: there is a newer image to pull. */
    UPDATE_AVAILABLE;

    /**
     * The verdict for one image, from the digest the container runs and the digest its registry currently
     * serves for the same tag.
     *
     * <p>This is <b>the</b> update-available decision, and it deliberately lives here rather than in the
     * sweep, the service or the browser: every consumer — the sweep that raises the email, the REST payload
     * the Explorer badges — must reach the same verdict from the same two facts, and the only way to
     * guarantee that is for none of them to decide it themselves.
     *
     * <p>Either digest missing or blank yields {@link #UNKNOWN}. Only two digests that are both present and
     * genuinely differ yield {@link #UPDATE_AVAILABLE}.
     */
    public static UpdateAvailability compare(String localDigest, String registryDigest) {
        if (isBlank(localDigest) || isBlank(registryDigest)) {
            return UNKNOWN;
        }
        return localDigest.strip().equals(registryDigest.strip()) ? UP_TO_DATE : UPDATE_AVAILABLE;
    }

    private static boolean isBlank(String digest) {
        return digest == null || digest.isBlank();
    }

    /** Whether the operator should be told to pull. True only for {@link #UPDATE_AVAILABLE}. */
    public boolean isUpdateAvailable() {
        return this == UPDATE_AVAILABLE;
    }
}
