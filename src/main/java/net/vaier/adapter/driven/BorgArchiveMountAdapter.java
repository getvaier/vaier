package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.BackupWorkDirResolver;
import net.vaier.domain.Archive;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupServer;
import net.vaier.domain.BorgCommand;
import net.vaier.domain.CommandResult;
import net.vaier.domain.MountedArchive;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForMountingArchives;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForPersistingBackupRepositories;
import net.vaier.domain.port.ForPersistingBackupServers;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForRunningSshCommands;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mounts a machine's past — a borg {@link Archive} — as a read-only FUSE filesystem on the machine itself,
 * over the ordinary SSH command path, so the Explorer can browse it with the same SFTP code it browses the
 * live tree with. It is the driven adapter behind {@link ForMountingArchives}: {@code ExplorerService} asks
 * for a mountpoint and never learns what borg is.
 *
 * <p>It holds no borg flags — every command string comes from {@link BorgCommand} — and no path rules — the
 * mountpoint and its coordinate mapping are {@link MountedArchive}'s. What it owns is orchestration: resolve
 * the machine's backup repository, provision the pass file, resolve the archive <em>name</em> from the
 * repository's {@code borg list} (the mountpoint is keyed by the archive <em>id</em>, but borg mounts by
 * name), and mount on demand.
 *
 * <p><b>Idempotent and lazy.</b> A directory browse calls {@link #mount} on every archive it opens, so a
 * cheap {@link BorgCommand#isMounted} probe short-circuits a warm re-browse to a single round trip — the
 * {@code borg list} + {@code borg mount} run only on a cold mount. Every mount records its last-access time
 * in an in-memory registry; {@link #unmountIdle} releases the ones untouched beyond the window, so a mount
 * never outlives its use on a fleet machine.
 *
 * <p>The work dir and the archive-name lookup are resolved through the same driven ports the run
 * orchestration uses, so there is exactly one way Vaier reaches a machine and one way it reads a repository.
 * Host-key trust is enforced by the run path (a pinned mismatch is refused; a first use is accepted), and
 * the very next SFTP listing in the same browse pins it — so this adapter does not re-pin.
 */
@Component
@Slf4j
public class BorgArchiveMountAdapter implements ForMountingArchives {

    private final ForResolvingSshTargets sshTargets;
    private final ForRunningSshCommands ssh;
    private final BackupWorkDirResolver workDirResolver;
    private final ForPersistingBackupJobs jobs;
    private final ForPersistingBackupRepositories repositories;
    private final ForPersistingBackupServers servers;
    private final Clock clock;

    /** Live mounts by mountpoint → when each was last browsed, so the sweep can release the idle ones. */
    private final Map<String, LiveMount> liveMounts = new ConcurrentHashMap<>();

    private record LiveMount(String machineName, MountedArchive mounted, Instant lastAccess) {
    }

    public BorgArchiveMountAdapter(ForResolvingSshTargets sshTargets, ForRunningSshCommands ssh,
                                   BackupWorkDirResolver workDirResolver, ForPersistingBackupJobs jobs,
                                   ForPersistingBackupRepositories repositories,
                                   ForPersistingBackupServers servers, Clock clock) {
        this.sshTargets = sshTargets;
        this.ssh = ssh;
        this.workDirResolver = workDirResolver;
        this.jobs = jobs;
        this.repositories = repositories;
        this.servers = servers;
        this.clock = clock;
    }

    @Override
    public MountedArchive mount(String machineName, String archiveId) {
        // MountedArchive.under validates the archive id (opaque hex) before any connection is opened.
        String workDir = workDirResolver.workDirFor(machineName);
        MountedArchive mounted = MountedArchive.under(workDir, archiveId);
        SshTarget target = sshTargets.resolve(machineName);

        if (!isAlreadyMounted(target, mounted)) {
            mountCold(machineName, target, mounted, workDir, archiveId);
        }
        touch(machineName, mounted);
        return mounted;
    }

    /** The cheap probe: is this mountpoint already an archive mount? A failed probe reads as "not mounted". */
    private boolean isAlreadyMounted(SshTarget target, MountedArchive mounted) {
        try {
            CommandResult probe = ssh.run(target, BorgCommand.isMounted(mounted.mountpoint()));
            return !probe.timedOut() && BorgCommand.parseMounted(probe.stdout());
        } catch (RuntimeException e) {
            log.debug("Mount probe of {} failed; assuming not mounted: {}", mounted.mountpoint(), e.getMessage());
            return false;
        }
    }

    /**
     * A cold mount: resolve the machine's repository, provision the pass file, read the archive's name from
     * {@code borg list} (the id keys the mountpoint; borg mounts by name), then mount it. Idempotent at the
     * command level too — {@link BorgCommand#mount} reuses an already-mounted mountpoint rather than failing.
     */
    private void mountCold(String machineName, SshTarget target, MountedArchive mounted, String workDir,
                           String archiveId) {
        BackupJob job = firstJobFor(machineName);
        BackupRepository repo = repositoryFor(job);
        BackupServer server = serverFor(repo);

        ensurePassFile(target, repo, workDir);
        String archiveName = archiveNameFor(target, server, repo, workDir, archiveId, machineName);

        BorgCommand.BuiltCommand mount = BorgCommand.mount(server, repo, archiveName, mounted.mountpoint(),
            workDir);
        log.info("Mounting archive {} of repository {} on {} at {}", archiveName, repo.name(), machineName,
            mounted.mountpoint());
        ssh.run(target, mount.exec());
    }

    private BackupJob firstJobFor(String machineName) {
        return jobs.getByMachine(machineName).stream().findFirst()
            .orElseThrow(() -> new NotFoundException(
                "Cannot browse the past of " + machineName + ": it has no backup job, so no archives"));
    }

    private BackupRepository repositoryFor(BackupJob job) {
        return repositories.getAll().stream()
            .filter(r -> r.name().equals(job.repositoryName())).findFirst()
            .orElseThrow(() -> new NotFoundException(
                "Backup repository " + job.repositoryName() + " is not configured"));
    }

    private BackupServer serverFor(BackupRepository repo) {
        return servers.getAll().stream()
            .filter(s -> s.name().equals(repo.serverName())).findFirst()
            .orElseThrow(() -> new NotFoundException(
                "Backup server " + repo.serverName() + " is not configured"));
    }

    /** The archive's borg name for the id being browsed — read from {@code borg list} of the repository. */
    private String archiveNameFor(SshTarget target, BackupServer server, BackupRepository repo, String workDir,
                                  String archiveId, String machineName) {
        BorgCommand.BuiltCommand list = BorgCommand.listArchives(server, repo, workDir);
        CommandResult result = ssh.run(target, list.exec());
        return Archive.parseList(result.stdout()).stream()
            .filter(a -> archiveId.equals(a.id())).map(Archive::name).findFirst()
            .orElseThrow(() -> new NotFoundException(
                "No archive " + archiveId + " in repository " + repo.name() + " for " + machineName));
    }

    /** Write-if-absent the repository pass file so borg's {@code BORG_PASSCOMMAND} can read the secret. */
    private void ensurePassFile(SshTarget target, BackupRepository repo, String workDir) {
        try {
            ssh.run(target, BorgCommand.ensurePassFile(repo, workDir).exec());
        } catch (RuntimeException e) {
            // Best-effort, mirroring the run/list path: a genuinely missing secret surfaces as a mount that
            // fails to unlock, not as an exception here.
            log.debug("Could not ensure pass file for repository {}: {}", repo.name(), e.getMessage());
        }
    }

    private void touch(String machineName, MountedArchive mounted) {
        liveMounts.put(mounted.mountpoint(), new LiveMount(machineName, mounted, clock.instant()));
    }

    @Override
    public void unmountIdle(long idleWindowMillis) {
        Instant cutoff = clock.instant().minus(Duration.ofMillis(idleWindowMillis));
        for (LiveMount live : List.copyOf(liveMounts.values())) {
            if (live.lastAccess().isBefore(cutoff)) {
                releaseMount(live);
            }
        }
    }

    /** Unmount one idle mount and forget it. A failure is logged, not thrown — the sweep must not break. */
    private void releaseMount(LiveMount live) {
        try {
            SshTarget target = sshTargets.resolve(live.machineName());
            ssh.run(target, BorgCommand.umount(live.mounted().mountpoint()));
            log.info("Released idle archive mount {} on {}", live.mounted().mountpoint(), live.machineName());
        } catch (RuntimeException e) {
            log.debug("Could not release idle mount {} on {}: {}",
                live.mounted().mountpoint(), live.machineName(), e.getMessage());
        } finally {
            liveMounts.remove(live.mounted().mountpoint());
        }
    }
}
