package net.vaier.domain;

/**
 * Thrown when a machine presents an SSH host key whose fingerprint differs from the one Vaier pinned
 * on first use (TOFU). Vaier refuses the connection rather than silently trusting the new key — the
 * host may have been legitimately rebuilt (clear the pin to re-pin) or this may be a man-in-the-middle.
 */
public class HostKeyMismatchException extends RuntimeException {
    private final String pinnedFingerprint;
    private final String presentedFingerprint;

    public HostKeyMismatchException(String machineName, String pinnedFingerprint, String presentedFingerprint) {
        super("Host key for \"" + machineName + "\" changed: pinned " + pinnedFingerprint
            + " but the host presented " + presentedFingerprint
            + ". If you rebuilt this host, clear its pinned key and reconnect.");
        this.pinnedFingerprint = pinnedFingerprint;
        this.presentedFingerprint = presentedFingerprint;
    }

    public String getPinnedFingerprint() {
        return pinnedFingerprint;
    }

    public String getPresentedFingerprint() {
        return presentedFingerprint;
    }
}
