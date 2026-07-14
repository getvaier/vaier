package net.vaier.domain;

import java.util.Optional;

/**
 * The SSH user's home directory on a machine, as the <em>exec</em> channel reports it: how Vaier asks
 * ({@link #PROBE_COMMAND}) and how the answer is read ({@link #in}).
 *
 * <p>Two parts of Vaier need this same fact. The backup work dir has resolved it since the backups shipped
 * — borg runs as the SSH user, who cannot write under {@code /var/lib}, so its state lives under the home.
 * The Explorer needs it too, as one half of the pair of answers that reveals where a machine's SFTP
 * subsystem is rooted ({@link SftpRoot}): the exec channel says {@code /volume1/homes/geir} where SFTP,
 * chrooted, says {@code /homes/geir}.
 *
 * <p>It lives here, once, rather than as a private constant in each caller. A machine has one identity and
 * one home; Vaier has been bitten before by several code paths quietly disagreeing about a machine, and a
 * second copy of this probe would be the beginning of exactly that.
 *
 * <p>An unusable answer is <b>no answer</b>, never a guess: a probe that timed out, exited non-zero, or came
 * back blank, relative, or with {@code $HOME} unexpanded resolves to empty. What a caller does about that is
 * its own decision — the backup run falls back to {@code /tmp}, the Explorer declines to map any path.
 */
public final class SshHome {

    /** Prints {@code $HOME} with {@code printf %s} — no trailing newline — so the resolved path is exact. */
    public static final String PROBE_COMMAND = "printf %s \"$HOME\"";

    /**
     * The same home, named <em>physically</em>: {@code $HOME} with every symlink on the way resolved away.
     *
     * <p>{@link #PROBE_COMMAND} answers with the name the machine advertises, and that name can be an alias.
     * On the NAS, {@code $HOME} is {@code /var/services/homes/geir} — a DSM symlink onto the real
     * {@code /volume1/homes/geir}. That is a perfectly good answer for anything that merely wants to
     * <em>reach</em> the home (the backup work dir does, and asks the question the plain way).
     *
     * <p>It is the wrong answer for {@link SftpRoot}. A chroot is a <b>physical</b> subtree of the filesystem,
     * so an aliased home can never line up with one: the jailed half of the NAS knows geir's home as
     * {@code /homes/geir}, which is a tail of {@code /volume1/homes/geir} and of no name containing
     * {@code /var/services}. To find a jail you must ask the machine where the home really <em>is</em>.
     *
     * <p>{@code cd} into it and print the physical working directory — POSIX, and it fails loudly (non-zero,
     * therefore {@link #in} empty) when the home does not exist, rather than answering with a path nothing is
     * at.
     */
    public static final String PHYSICAL_PROBE_COMMAND = "cd \"$HOME\" && pwd -P";

    private SshHome() {
    }

    /** The absolute {@code $HOME} in a {@link #PROBE_COMMAND} result, or empty when it did not answer with one. */
    public static Optional<String> in(CommandResult result) {
        if (result == null || result.timedOut() || result.exitCode() != 0 || result.stdout() == null) {
            return Optional.empty();
        }
        String home = result.stdout().strip();
        if (home.isBlank() || !home.startsWith("/")) {
            return Optional.empty();
        }
        return Optional.of(home);
    }
}
