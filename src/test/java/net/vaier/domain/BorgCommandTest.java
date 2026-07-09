package net.vaier.domain;

import net.vaier.domain.BorgCommand.BuiltCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BorgCommandTest {

    /** The work dir the orchestration resolves and passes in (formerly WORK_DIR). */
    private static final String WORK_DIR = "/var/lib/vaier-backup";

    private BackupRepository repo() {
        return new BackupRepository("nas-borg", "192.168.3.3", 8022, "borg", "./colina", "s3cr3t", false);
    }

    private BackupJob job() {
        return new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir", "/etc"), List.of("*.tmp", "/var/cache"),
            7, 4, 6, "zstd,6", true);
    }

    /** The passcommand a run/list/init uses to fetch the passphrase from the provisioned 0600 file. */
    private String expectedPasscommand() {
        return "export BORG_PASSCOMMAND='cat /var/lib/vaier-backup/nas-borg.pass'";
    }

    @Test
    void createRendersRepoUrlArchiveTemplateCompressionAndExcludes() {
        BuiltCommand cmd = BorgCommand.create(job(), repo(), WORK_DIR);

        assertThat(cmd.exec())
            .contains("borg create --json --stats --compression zstd,6 --exclude-caches")
            .contains("--exclude '*.tmp'")
            .contains("--exclude '/var/cache'")
            .contains("ssh://borg@192.168.3.3:8022/./colina::'{hostname}-{now:%Y-%m-%dT%H:%M:%S}'")
            .contains("'/home/geir'")
            .contains("'/etc'");
        // Sources come after the repo::archive target.
        assertThat(cmd.exec().indexOf("::'{hostname}"))
            .isLessThan(cmd.exec().indexOf("'/home/geir'"));
    }

    @Test
    void createUsesPasscommandNotPlaintextPassphrase() {
        BuiltCommand cmd = BorgCommand.create(job(), repo(), WORK_DIR);

        // The passphrase is NOT exported into the environment or placed on borg's argv: a passcommand
        // reads it from the provisioned 0600 file instead, so the secret never appears in the command.
        assertThat(cmd.exec()).contains(expectedPasscommand());
        assertThat(cmd.exec()).doesNotContain("BORG_PASSPHRASE").doesNotContain("s3cr3t");
        // No secret means nothing to mask: the redacted twin equals exec.
        assertThat(cmd.redacted()).isEqualTo(cmd.exec()).doesNotContain("s3cr3t");
    }

    @Test
    void detachedRunBackgroundsWithNohupWritesRcAndLogAndReturnsImmediately() {
        BuiltCommand cmd = BorgCommand.detachedRun(job(), repo(), "run-1", WORK_DIR);

        String exec = cmd.exec();
        // Persistent work dir is set up before launch.
        assertThat(exec).contains("W=/var/lib/vaier-backup").contains("mkdir -p \"$W\"");
        // borg is detached with nohup and backgrounded, then STARTED is echoed so the exec returns fast.
        assertThat(exec).contains("nohup sh -c \"");
        assertThat(exec).contains("& echo STARTED $!");
        // The chain writes its exit code and output to persistent per-run files.
        assertThat(exec).contains("echo \\$? > $W/run-1.rc");
        assertThat(exec).contains("> \"$W/run-1.log\" 2>&1");
        // The passphrase leaves argv/env for good: a passcommand reads it from the 0600 file, and the
        // plaintext never appears anywhere in the run command.
        assertThat(exec).contains(expectedPasscommand());
        assertThat(exec).doesNotContain("BORG_PASSPHRASE").doesNotContain("s3cr3t");
        assertThat(exec.indexOf("sh -c \"")).isLessThan(exec.indexOf("export BORG_PASSCOMMAND"));
        assertThat(exec.indexOf("export BORG_PASSCOMMAND")).isLessThan(exec.indexOf("borg create"));
        // Slice 5: the inner chain grew to create && prune && compact (a focused test asserts the shape).
        assertThat(exec).contains("borg create --json --stats").contains("borg prune").contains("borg compact");

        // No secret in the command -> redacted twin equals exec.
        assertThat(cmd.redacted()).isEqualTo(exec).doesNotContain("s3cr3t");
    }

    @Test
    void detachedRunChainsCreateThenPruneScopedByGlobThenCompact() {
        BuiltCommand cmd = BorgCommand.detachedRun(job(), repo(), "run-1", WORK_DIR);
        String exec = cmd.exec();

        // create && prune && compact, in that order.
        int create = exec.indexOf("borg create --json --stats");
        int prune = exec.indexOf("borg prune --list");
        int compact = exec.indexOf("borg compact ");
        assertThat(create).isGreaterThanOrEqualTo(0);
        assertThat(prune).isGreaterThan(create);
        assertThat(compact).isGreaterThan(prune);
        // Chained with && so prune only runs on a good create, compact only after a good prune.
        assertThat(exec).contains("&& borg prune").contains("&& borg compact");
        // Prune is scoped to THIS job's archives ({hostname}-*) so a shared repo never cross-deletes
        // another host's archives, and carries the job's retention counts.
        assertThat(exec).contains("borg prune --list --glob-archives '{hostname}-*' "
            + "--keep-daily 7 --keep-weekly 4 --keep-monthly 6 "
            + "ssh://borg@192.168.3.3:8022/./colina");
        // Compact targets the same repo URL (borg >= 1.2).
        assertThat(exec).contains("borg compact ssh://borg@192.168.3.3:8022/./colina");
        // The passphrase never appears; the redacted twin equals exec.
        assertThat(exec).contains(expectedPasscommand()).doesNotContain("s3cr3t");
        assertThat(cmd.redacted()).isEqualTo(exec);
    }

    @Test
    void listArchivesBuildsBorgListJson() {
        BuiltCommand cmd = BorgCommand.listArchives(repo(), WORK_DIR);
        String exec = cmd.exec();

        // Wrapped in sh -c with the passphrase supplied via a passcommand (from the 0600 file), then
        // borg list --json on the repo URL. Runs over the normal 20 s exec (listing is fast), not detached.
        assertThat(exec).startsWith("sh -c \"");
        assertThat(exec).contains(expectedPasscommand());
        assertThat(exec).contains("borg list --json ssh://borg@192.168.3.3:8022/./colina");
        assertThat(exec.indexOf("export BORG_PASSCOMMAND")).isLessThan(exec.indexOf("borg list"));
        assertThat(exec).doesNotContain("BORG_PASSPHRASE").doesNotContain("s3cr3t");

        // No secret -> redacted twin equals exec.
        assertThat(cmd.redacted()).isEqualTo(exec).doesNotContain("s3cr3t");
    }

    @Test
    void pollStatusAndFetchLogReferenceThePersistentRunFiles() {
        String poll = BorgCommand.pollStatus("run-1", WORK_DIR);
        assertThat(poll)
            .contains("[ -f \"/var/lib/vaier-backup/run-1.rc\" ]")
            .contains("echo DONE $(cat \"/var/lib/vaier-backup/run-1.rc\")")
            .contains("echo RUNNING");

        String fetch = BorgCommand.fetchLog("run-1", WORK_DIR);
        assertThat(fetch).contains("tail -c 4096 \"/var/lib/vaier-backup/run-1.log\"");
    }

    @Test
    void parsePollReadsRunningAndDone() {
        assertThat(BorgCommand.parsePoll("RUNNING")).isEmpty();
        assertThat(BorgCommand.parsePoll("RUNNING\n")).isEmpty();
        assertThat(BorgCommand.parsePoll("DONE 0")).contains(0);
        assertThat(BorgCommand.parsePoll("DONE 2\n")).contains(2);
        assertThat(BorgCommand.parsePoll(null)).isEmpty();
        assertThat(BorgCommand.parsePoll("")).isEmpty();
        // A DONE with no code cannot be resolved yet.
        assertThat(BorgCommand.parsePoll("DONE")).isEqualTo(Optional.empty());
    }

    // --- Slice 8: provisioning command lines + passphrase hardening ---

    @Test
    void versionAndReachabilityAndInit() {
        // Plain version probe.
        assertThat(BorgCommand.versionProbe()).isEqualTo("borg --version");

        // Reachability probe: a bounded /dev/tcp connect to the NAS borg port over the tunnel.
        String reach = BorgCommand.reachabilityProbe(repo());
        assertThat(reach).isEqualTo(
            "timeout 5 bash -c 'cat </dev/null >/dev/tcp/192.168.3.3/8022' && echo NAS_OPEN || echo NAS_CLOSED");
        // Its parser reads NAS_OPEN as reachable, anything else as not.
        assertThat(BorgCommand.parseReachability("NAS_OPEN")).isTrue();
        assertThat(BorgCommand.parseReachability("NAS_OPEN\n")).isTrue();
        assertThat(BorgCommand.parseReachability("NAS_CLOSED")).isFalse();
        assertThat(BorgCommand.parseReachability("")).isFalse();
        assertThat(BorgCommand.parseReachability(null)).isFalse();

        // Init: repokey-blake2 encryption, parent dirs made, on the repo URL — and the passphrase supplied
        // via the same passcommand form so init never puts the secret on argv/env either.
        BuiltCommand init = BorgCommand.init(repo(), WORK_DIR);
        assertThat(init.exec())
            .contains(expectedPasscommand())
            .contains("borg init --encryption=repokey-blake2 --make-parent-dirs "
                + "ssh://borg@192.168.3.3:8022/./colina");
        assertThat(init.exec()).doesNotContain("BORG_PASSPHRASE").doesNotContain("s3cr3t");
        assertThat(init.redacted()).isEqualTo(init.exec());
    }

    @Test
    void writePassFileIsZeroSixHundredAndRedacted() {
        // The install-secret command is the ONLY place the plaintext lands on the host: a 0600 file under
        // the work dir, written with a tight umask; the redacted twin masks the passphrase for logging.
        BuiltCommand write = BorgCommand.writePassFile(repo(), WORK_DIR);

        assertThat(write.exec())
            .contains("umask 077")
            .contains("printf %s 's3cr3t'")
            .contains("/var/lib/vaier-backup/nas-borg.pass");
        // Redacted masks the secret and otherwise matches exec.
        assertThat(write.redacted())
            .contains("printf %s '***'")
            .doesNotContain("s3cr3t");
        assertThat(write.redacted().replace("***", "s3cr3t")).isEqualTo(write.exec());
    }

    @Test
    void ensurePassFileWritesOnlyWhenAbsentAndIsRedacted() {
        // The runner ensures the pass file before a launch: a write-if-absent so a run never fails for a
        // missing secret file, without rewriting it every run. Still masks the passphrase when logged.
        BuiltCommand ensure = BorgCommand.ensurePassFile(repo(), WORK_DIR);

        assertThat(ensure.exec())
            .contains("[ -f \"/var/lib/vaier-backup/nas-borg.pass\" ]")
            .contains("umask 077")
            .contains("printf %s 's3cr3t'");
        assertThat(ensure.redacted())
            .contains("printf %s '***'")
            .doesNotContain("s3cr3t");
        assertThat(ensure.redacted().replace("***", "s3cr3t")).isEqualTo(ensure.exec());
    }

    @Test
    void parseInitDetectsAlreadyExistsAsIdempotent() {
        // borg exits non-zero when the repo already exists; that specific message is a success (idempotent),
        // not a failure.
        assertThat(BorgCommand.isRepositoryAlreadyExists(
            "A repository already exists at ssh://borg@192.168.3.3:8022/./colina.")).isTrue();
        assertThat(BorgCommand.isRepositoryAlreadyExists("Repository already exists.")).isTrue();
        assertThat(BorgCommand.isRepositoryAlreadyExists("Connection refused")).isFalse();
        assertThat(BorgCommand.isRepositoryAlreadyExists("")).isFalse();
        assertThat(BorgCommand.isRepositoryAlreadyExists(null)).isFalse();
    }
}
