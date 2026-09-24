package net.vaier.domain;

/**
 * Vaier asked CrowdSec to block an address by hand and could not tell that it had (#349).
 *
 * <p>The mirror of {@link BlockNotLiftedException}, for the same reason: an operator who just clicked
 * "Block" is standing there waiting to learn whether the address is actually kept out, and a swallowed
 * failure would tell them it is when it is not.
 *
 * <p>Surfaces as {@code 502} — the failure is on the far side of Vaier, in the engine that owns the ban,
 * exactly like {@link BlockNotLiftedException} and {@link BlockDecisionsUnreadableException}.
 */
public class BlockNotPlacedException extends RuntimeException {

    public BlockNotPlacedException(String message) {
        super(message);
    }

    public BlockNotPlacedException(String message, Throwable cause) {
        super(message, cause);
    }
}
