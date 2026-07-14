package net.vaier.adapter.driven;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.CommandResult;
import net.vaier.domain.SftpRoot;
import net.vaier.domain.SshHome;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForResolvingSftpRoots;
import net.vaier.domain.port.ForRunningSshCommands;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks a machine where its SFTP subsystem is rooted, and remembers the answer (#326).
 *
 * <p>It puts the same question to both halves of the machine — <em>where is the SSH user's home?</em> — over
 * the two channels that disagree about it: the exec channel ({@link SshHome#PROBE_COMMAND}, the very probe the
 * backups have resolved {@code $HOME} with since they shipped) and the SFTP channel's own canonical path of
 * {@code .}. On the NAS the first says {@code /volume1/homes/geir} and the second says {@code /homes/geir},
 * and the difference between them is the jail.
 *
 * <p><b>It decides nothing.</b> It probes, it hands the two strings to {@link SftpRoot#resolve}, and it caches
 * what the domain makes of them. Whether a machine is jailed, and where, is a decision — and it lives on the
 * domain, not here.
 *
 * <p><b>What is remembered, and what is not.</b> A machine that answered is cached: a root does not move, and
 * without the cache every directory the operator clicked would cost two extra SSH connections to a machine on
 * the far side of a VPN. That includes a machine that answered something unreadable — two homes that do not
 * line up is a stable fact about the machine, not a blip, and re-asking forever would buy nothing. But a
 * machine that could not be <em>reached</em> is not cached, exactly as {@code BackupWorkDirResolver} declines
 * to cache a failed home probe: a host that was merely asleep must get its real root the next time it is
 * awake, not be branded rootless for the lifetime of the process.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CachingSftpRootAdapter implements ForResolvingSftpRoots {

    private final ForRunningSshCommands forRunningSshCommands;
    private final ForBrowsingRemoteFiles forBrowsingRemoteFiles;

    /** machineName -> the root its SFTP subsystem is chrooted into (often {@link SftpRoot#NONE}). */
    private final Map<String, SftpRoot> roots = new ConcurrentHashMap<>();

    @Override
    public SftpRoot rootFor(String machineName, SshTarget target) {
        SftpRoot cached = roots.get(machineName);
        if (cached != null) {
            return cached;
        }
        return probe(machineName, target)
            .map(root -> {
                roots.put(machineName, root);
                return root;
            })
            // Deliberately NOT cached: the machine could not be reached, and a blip must never brand it.
            .orElse(SftpRoot.NONE);
    }

    /**
     * Both answers, put to the domain — or empty when the machine could not be asked at all. A machine that
     * answers with two homes that do not line up has still <em>answered</em>: that resolves to
     * {@link SftpRoot#NONE} and is worth remembering, so it comes back present, not empty.
     */
    private Optional<SftpRoot> probe(String machineName, SshTarget target) {
        try {
            Optional<String> execHome = SshHome.in(exec(target));
            if (execHome.isEmpty()) {
                log.debug("Could not read the physical home over the exec channel on {}; leaving its paths "
                    + "alone", machineName);
                return Optional.empty();
            }
            String trueHome = execHome.get();

            // The direct question first, and on a well-behaved machine it is the only one asked: SFTP
            // canonicalises "." to the SSH user's home, and the difference from the machine's own name for it
            // is the jail. Every unjailed machine in the fleet is answered here, at one probe apiece.
            Optional<SftpRoot> direct = SftpRoot.resolve(trueHome, forBrowsingRemoteFiles.home(target));
            if (direct.isPresent()) {
                return Optional.of(announce(machineName, direct.get()));
            }

            // The NAS does not oblige: it answers "/" — the jail root itself, which says nothing about where
            // that root is. So the home is located instead. It is the one directory both channels genuinely
            // share, and the domain says which names the jailed half might know it by, longest first.
            Optional<String> jailedHome =
                forBrowsingRemoteFiles.firstDirectory(target, SftpRoot.jailCandidates(trueHome));
            if (jailedHome.isEmpty()) {
                log.warn("{} says its home is {}, but its SFTP service can see no part of that path — so "
                    + "Vaier will not guess where that service is rooted, and leaves the machine's paths as "
                    + "they are.", machineName, trueHome);
                return Optional.of(SftpRoot.NONE);
            }
            return Optional.of(announce(machineName,
                SftpRoot.resolve(trueHome, jailedHome.get()).orElse(SftpRoot.NONE)));

        } catch (RuntimeException e) {
            // Unreachable, unauthenticated, a changed host key — every one of them an ordinary state of a
            // fleet. None of them is a reason to fail the browse: the listing itself is about to be attempted
            // and will report the real failure in its own words. Here it only means "root unknown".
            log.debug("Could not resolve the SFTP root of {}: {}", machineName, e.getMessage());
            return Optional.empty();
        }
    }

    /** A jail is worth saying out loud once — it changes every path Vaier shows for the machine. */
    private static SftpRoot announce(String machineName, SftpRoot root) {
        if (root.jailed()) {
            log.info("{}'s SFTP service is rooted at {} — its files are shown at their real paths",
                machineName, root.path());
        }
        return root;
    }

    /**
     * The SSH user's home, named <b>physically</b>. {@code $HOME} on the NAS is {@code /var/services/homes/geir}
     * — a symlink onto {@code /volume1/homes/geir} — and a jail is a physical subtree, so only the resolved
     * name can ever line up with one. Same port, same channel, same probe the backups run: a sharper question,
     * not another way in.
     */
    private CommandResult exec(SshTarget target) {
        return forRunningSshCommands.run(target, SshHome.PHYSICAL_PROBE_COMMAND);
    }
}
