package net.vaier.domain;

/**
 * Thrown when a lookup for a requested resource fails. Distinct from
 * {@link IllegalArgumentException} (validation → 400) and from a generic failure
 * (→ 500) so the REST layer's GlobalExceptionHandler can map it to {@code 404}
 * unambiguously. Prefer this over {@link java.util.NoSuchElementException}, which is
 * also raised by ordinary programming errors (e.g. {@code Optional.get()}) and must
 * stay a 500.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
