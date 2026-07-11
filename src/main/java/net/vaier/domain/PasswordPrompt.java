package net.vaier.domain;

import java.util.regex.Pattern;

/**
 * The domain rule for the one question that decides whether it is safe to send a stored SSH password
 * into a terminal: <b>is the remote actually awaiting a password right now?</b>
 *
 * <p>A password written into a PTY is just keystrokes. If the remote is not at a password prompt it
 * echoes them into the terminal (visible in the browser) and can land in shell history or be executed —
 * so the secret must only ever be sent when the <em>tail</em> of the recent output looks like a live
 * password prompt. A {@code password:} far up the scrollback (already answered) must not count, which is
 * why the match is anchored to the end of the output.
 */
public final class PasswordPrompt {

    /**
     * Matches a password/passphrase prompt only at the very end of the output. {@code (?is)} makes it
     * case-insensitive and lets {@code .*} span newlines; the alternation covers the bare
     * {@code password:} / {@code user@host's password:} forms, sudo's {@code password for <user>:}, and
     * ssh's {@code Enter passphrase for key '...':} form (the key path can hold spaces/quotes, so
     * {@code .+} runs up to the anchoring colon). {@code \s*\z} allows an optional trailing space or
     * newline but nothing else after the prompt, so a shell prompt printed after an answered password no
     * longer matches.
     */
    private static final Pattern AWAITING_PASSWORD =
        Pattern.compile("(?is).*(?:password(?: for \\S+)?|passphrase for .+):\\s*\\z");

    private PasswordPrompt() {
    }

    /** True when the tail of {@code recentOutput} is a live password prompt; false for null/blank. */
    public static boolean isAwaitingPassword(String recentOutput) {
        if (recentOutput == null || recentOutput.isBlank()) {
            return false;
        }
        return AWAITING_PASSWORD.matcher(recentOutput).matches();
    }
}
