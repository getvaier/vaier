package net.vaier.domain;

/**
 * Thrown when an operation would strip the access store of its last administrator — revoking the sole
 * admin, or demoting it to a non-admin role. With the console admin-only and no Authelia fallback,
 * losing the last admin would lock everyone out permanently, so the invariant is enforced as a hard
 * refusal. Surfaced to the web layer as {@code 409 Conflict}.
 */
public class LastAdminException extends IllegalStateException {
    public LastAdminException(String message) {
        super(message);
    }
}
