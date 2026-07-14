package net.vaier.domain;

/**
 * The machine refused the read: the SSH user Vaier authenticates as is not allowed to see that path.
 *
 * <p>This is an ordinary fact about a fleet, not a fault. Vaier's SSH users are deliberately not root on most
 * machines, so whole swathes of a filesystem are unreadable — and an operator browsing them needs to be told
 * "you cannot read this", not handed a server error that reads as though Vaier broke.
 */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String message) {
        super(message);
    }

    public PermissionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
