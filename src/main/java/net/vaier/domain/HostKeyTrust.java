package net.vaier.domain;

/**
 * The trust decision for an SSH server's host key on connect — trust-on-first-use (TOFU). Comparing a
 * newly presented host-key fingerprint against the one Vaier has pinned for a machine is a domain
 * decision, so it lives here rather than in the SSH adapter.
 */
public enum HostKeyTrust {
    /** No fingerprint was pinned yet — pin the presented one (first use). */
    PIN_NEW,
    /** The presented fingerprint equals the pinned one — trust it. */
    MATCH,
    /** A fingerprint is pinned and the presented one differs — refuse the connection. */
    MISMATCH;

    /**
     * The trust of a {@code presented} fingerprint given the {@code pinned} one (null/blank = none
     * pinned yet). {@link #PIN_NEW} when nothing is pinned, {@link #MATCH} when they are equal,
     * {@link #MISMATCH} otherwise.
     */
    public static HostKeyTrust evaluate(String pinned, String presented) {
        if (pinned == null || pinned.isBlank()) {
            return PIN_NEW;
        }
        return pinned.equals(presented) ? MATCH : MISMATCH;
    }

    /** True when the connection may proceed — a first-use pin or a matching key, never a mismatch. */
    public boolean isTrusted() {
        return this != MISMATCH;
    }
}
