package net.vaier.domain;

/**
 * Thrown when a VPN peer lookup by name or IP fails. Distinct from
 * {@link IllegalArgumentException} (which the REST layer maps to 400 for
 * validation errors) so the controller can return 404 unambiguously.
 */
public class PeerNotFoundException extends RuntimeException {
    public PeerNotFoundException(String message) {
        super(message);
    }
}
