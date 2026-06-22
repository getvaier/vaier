package net.vaier.domain;

/**
 * Thrown when a VPN peer lookup by name or IP fails. A {@link NotFoundException}, so the
 * REST layer's GlobalExceptionHandler maps it to {@code 404} via the same handler as every
 * other not-found; kept as a distinct type for peer-specific call sites and messages.
 */
public class PeerNotFoundException extends NotFoundException {
    public PeerNotFoundException(String message) {
        super(message);
    }
}
