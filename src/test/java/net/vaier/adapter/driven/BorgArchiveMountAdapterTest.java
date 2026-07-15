package net.vaier.adapter.driven;

import net.vaier.application.BackupWorkDirResolver;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupServer;
import net.vaier.domain.CommandResult;
import net.vaier.domain.HostCredential;
import net.vaier.domain.MountedArchive;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForPersistingBackupRepositories;
import net.vaier.domain.port.ForPersistingBackupServers;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForRunningSshCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The borg-mount adapter: it turns "browse this machine's archive" into a mountpoint, resolving the
 * machine's backup repository, mounting on demand (idempotently), and reading the archive name from the
 * repository's {@code borg list} — all over the ordinary SSH command path. It holds no borg flags (those
 * are {@link net.vaier.domain.BorgCommand}'s) and no path rules (those are {@link MountedArchive}'s); it
 * orchestrates and tracks live mounts so they can be swept when idle.
 */
class BorgArchiveMountAdapterTest {

    private ForResolvingSshTargets sshTargets;
    private ForRunningSshCommands ssh;
    private BackupWorkDirResolver workDirResolver;
    private ForPersistingBackupJobs jobs;
    private ForPersistingBackupRepositories repositories;
    private ForPersistingBackupServers servers;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-15T10:00:00Z"));

    private BorgArchiveMountAdapter adapter;

    private static final String MACHINE = "Apalveien 5";
    private static final String ARCHIVE_ID = "9f8e7d6c";
    private static final String ARCHIVE_NAME = "apalveien5-2026-07-14T02:00:00";
    private static final String WORK_DIR = "/home/ubuntu/.vaier-backup";
    private static final String MOUNTPOINT = WORK_DIR + "/mounts/" + ARCHIVE_ID;

    private static final String LIST_JSON = """
        { "archives": [
          { "archive": "%s", "id": "%s", "time": "2026-07-14T02:00:00.000000" }
        ] }
        """.formatted(ARCHIVE_NAME, ARCHIVE_ID);

    @BeforeEach
    void setUp() {
        sshTargets = mock(ForResolvingSshTargets.class);
        ssh = mock(ForRunningSshCommands.class);
        workDirResolver = mock(BackupWorkDirResolver.class);
        jobs = mock(ForPersistingBackupJobs.class);
        repositories = mock(ForPersistingBackupRepositories.class);
        servers = mock(ForPersistingBackupServers.class);
        // A clock that reads the mutable `now` so a test can advance time for the idle sweep.
        Clock movingClock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now.get(); }
        };
        adapter = new BorgArchiveMountAdapter(sshTargets, ssh, workDirResolver, jobs, repositories, servers,
            movingClock);

        when(workDirResolver.workDirFor(MACHINE)).thenReturn(WORK_DIR);
        when(sshTargets.resolve(MACHINE)).thenReturn(target());
        when(jobs.getByMachine(MACHINE)).thenReturn(List.of(job()));
        when(repositories.getAll()).thenReturn(List.of(repo()));
        when(servers.getAll()).thenReturn(List.of(server()));
    }

    private static SshTarget target() {
        return SshTarget.on("10.13.13.6",
            new HostCredential("Apalveien 5", "ubuntu", AuthMethod.PASSWORD, "pw", null, false), "SHA256:pinned");
    }

    private static BackupJob job() {
        return new BackupJob("apalveien-home", MACHINE, "apalveien",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
    }

    private static BackupRepository repo() {
        return new BackupRepository("apalveien", "nas-borg", null, "s3cr3t", false);
    }

    private static BackupServer server() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    private CommandResult ok(String stdout) {
        return new CommandResult(0, stdout, "", false, "SHA256:pinned");
    }

    /** Route each SSH command to a canned result by what it is (probe / list / mount / pass file). */
    private void sshBehaves(boolean alreadyMounted) {
        when(ssh.run(any(), any())).thenAnswer(inv -> {
            String cmd = inv.getArgument(1);
            if (cmd.contains("echo IS_MOUNTED")) {
                return ok(alreadyMounted ? "IS_MOUNTED" : "NOT_MOUNTED");
            }
            if (cmd.contains("borg list")) {
                return ok(LIST_JSON);
            }
            if (cmd.contains("borg mount")) {
                return ok("MOUNTED");
            }
            return ok(""); // ensure-pass-file and anything else
        });
    }

    @Test
    void mount_coldMount_mountsTheArchiveByName_intoTheIdKeyedMountpoint() {
        sshBehaves(false);

        MountedArchive mounted = adapter.mount(MACHINE, ARCHIVE_ID);

        assertThat(mounted.mountpoint()).isEqualTo(MOUNTPOINT);
        // The archive name came from `borg list`, and the mount addressed REPO::ARCHIVE into the mountpoint.
        verify(ssh).run(any(), contains("borg list"));
        verify(ssh).run(any(), contains("borg mount"));
        verify(ssh).run(any(), contains("'" + ARCHIVE_NAME + "'"));
        // The mountpoint appears in both the probe and the mount command.
        verify(ssh, org.mockito.Mockito.atLeastOnce()).run(any(), contains("'" + MOUNTPOINT + "'"));
    }

    @Test
    void mount_ensuresThePassFile_beforeBorgNeedsTheSecret() {
        sshBehaves(false);

        adapter.mount(MACHINE, ARCHIVE_ID);

        // Same discipline as list/create: the 0600 pass file is provisioned so borg's BORG_PASSCOMMAND reads it.
        verify(ssh).run(any(), contains("umask 077"));
    }

    @Test
    void mount_whenAlreadyMounted_shortCircuits_withoutListingOrMounting() {
        sshBehaves(true);

        MountedArchive mounted = adapter.mount(MACHINE, ARCHIVE_ID);

        assertThat(mounted.mountpoint()).isEqualTo(MOUNTPOINT);
        // A warm re-browse costs one probe round trip — never another borg list or borg mount.
        verify(ssh, never()).run(any(), contains("borg list"));
        verify(ssh, never()).run(any(), contains("borg mount"));
    }

    @Test
    void mount_anArchiveIdTheRepositoryDoesNotHold_isNotFound() {
        sshBehaves(false);

        assertThatThrownBy(() -> adapter.mount(MACHINE, "deadbeef"))
            .isInstanceOf(NotFoundException.class);
        // A non-existent archive is never mounted.
        verify(ssh, never()).run(any(), contains("borg mount"));
    }

    @Test
    void mount_aMachineWithNoBackupJob_isNotFound() {
        when(jobs.getByMachine(MACHINE)).thenReturn(List.of());
        // The probe still runs (mountpoint is derivable), but there is no repository to mount from.
        when(ssh.run(any(), any())).thenReturn(ok("NOT_MOUNTED"));

        assertThatThrownBy(() -> adapter.mount(MACHINE, ARCHIVE_ID))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void mount_anIllFormedArchiveId_isRejectedBeforeAnyConnection() {
        assertThatThrownBy(() -> adapter.mount(MACHINE, "../etc"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(ssh, never()).run(any(), any());
    }

    @Test
    void unmountIdle_unmountsMountsUntouchedBeyondTheWindow_andLeavesFreshOnesAlone() {
        sshBehaves(false);
        adapter.mount(MACHINE, ARCHIVE_ID); // touched at 10:00:00

        // 20 minutes later, sweeping a 15-minute idle window releases it.
        now.set(Instant.parse("2026-07-15T10:20:00Z"));
        adapter.unmountIdle(Duration.ofMinutes(15).toMillis());

        verify(ssh).run(any(), contains("borg umount"));
        verify(ssh).run(any(), contains("rmdir '" + MOUNTPOINT + "'"));
    }

    @Test
    void unmountIdle_leavesAFreshlyUsedMountMounted() {
        sshBehaves(false);
        adapter.mount(MACHINE, ARCHIVE_ID); // touched at 10:00:00

        // Only 5 minutes later: still within the 15-minute window, so nothing is unmounted.
        now.set(Instant.parse("2026-07-15T10:05:00Z"));
        adapter.unmountIdle(Duration.ofMinutes(15).toMillis());

        verify(ssh, never()).run(any(), contains("borg umount"));
    }

    @Test
    void unmountIdle_reBrowsingRefreshesTheIdleClock() {
        sshBehaves(false);
        adapter.mount(MACHINE, ARCHIVE_ID); // touched at 10:00:00

        now.set(Instant.parse("2026-07-15T10:10:00Z"));
        adapter.mount(MACHINE, ARCHIVE_ID); // re-touched at 10:10:00 (warm-ish; probe says not mounted, remounts idempotently)

        now.set(Instant.parse("2026-07-15T10:20:00Z")); // 10 min after the last touch, inside a 15-min window
        adapter.unmountIdle(Duration.ofMinutes(15).toMillis());

        verify(ssh, never()).run(any(), contains("borg umount"));
    }
}
