package net.vaier.domain;

/**
 * Thrown when a request conflicts with current state (e.g. a name already taken, a LAN
 * CIDR already owned). Distinct from {@link IllegalStateException} — which is also raised
 * for genuine server faults (missing resources, unavailable providers) that must stay a
 * 500 — so the REST layer's GlobalExceptionHandler can map it to {@code 409} unambiguously.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
