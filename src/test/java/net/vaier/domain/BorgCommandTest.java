package net.vaier.domain;

import net.vaier.domain.BorgCommand.BuiltCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BorgCommandTest {

    /** The work dir the orchestration resolves and passes in (formerly WORK_DIR). */
    private static final String WORK_DIR = "/var/lib/vaier-backup";

    /** The SSH user's absolute home, resolved by the orchestration (BackupWorkDirResolver). */
    private static final String SSH_HOME = "/home/ubuntu";

    private BackupServer server() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    private BackupRepository repo() {
        // Explicit path override "./colina" so the rendered URL stays ssh://borg@192.168.3.3:8022/./colina.
        return new BackupRepository("nas-borg", "nas-borg", "./colina", "s3cr3t", false);
    }

    private BackupJob job() {
        return new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir", "/etc"), List.of("*.tmp", "/var/cache"),
            7, 4, 6, "zstd,6", true, false);
    }

    /** The same job, opted in to "Back up as root" — borg runs under sudo on the machine. */
    private BackupJob rootJob() {
        return new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir", "/etc"), List.of("*.tmp", "/var/cache"),
            7, 4, 6, "zstd,6", true, true);
    }

    /** The passcommand a run/list/init uses to fetch the passphrase from the provisioned 0600 file. */
    private String expectedPasscommand() {
        return "export BORG_PASSCOMMAND='cat /var/lib/vaier-backup/nas-borg.pass'";
    }

    // --- Back up as root: borg under sudo, so root-owned files are read rather than silently skipped ---

    /**
     * The regression pin. A job WITHOUT "Back up as root" must render byte-for-byte the command it rendered
     * before the feature existed — every existing job on every host keeps running exactly as it did, and no
     * job is ever escalated to root by the mere presence of the toggle.
     */
    @Test
    void detachedRun_withoutBackupAsRoot_isByteForByteUnchanged() {
        String exec = BorgCommand.detachedRun(server(), job(), repo(), "run-1", WORK_DIR, SSH_HOME).exec();

        String url = "'ssh://borg@192.168.3.3:8022/./colina'";
        assertThat(exec).isEqualTo(
            "W=/var/lib/vaier-backup; mkdir -p \"$W\"; nohup sh -c \""
                + "export BORG_PASSCOMMAND='cat /var/lib/vaier-backup/nas-borg.pass'; "
                + "borg info " + url + " > /dev/null 2>&1 || "
                + "borg init --encryption=repokey-blake2 --make-parent-dirs " + url
                + " && borg create --json --stats --compression zstd,6 --exclude-caches"
                + " --exclude '*.tmp' --exclude '/var/cache' "
                + url + "::'{hostname}-{now:%Y-%m-%dT%H:%M:%S}' '/home/geir' '/etc'"
                + " && borg prune --list --glob-archives '{hostname}-*'"
                + " --keep-daily 7 --keep-weekly 4 --keep-monthly 6 " + url
                + " && borg compact " + url
                + "; echo \\$? > $W/run-1.rc\" > \"$W/run-1.log\" 2>&1 & echo STARTED $!");
        // Not a whiff of sudo anywhere for a normal job.
        assertThat(exec).doesNotContain("sudo").doesNotContain("BORG_BASE_DIR").doesNotContain("HOME=");
    }

    /**
     * Every borg invocation in the run chain — info, init, create, prune, compact — runs under {@code sudo -n}
     * when the job is "Back up as root". Missing any one of them would half-escalate the run: e.g. a create as
     * root writing a repo the non-root prune then cannot manage.
     */
    @Test
    void detachedRun_whenBackupAsRoot_runsEveryBorgInvocationInTheChainUnderSudo() {
        String exec = BorgCommand.detachedRun(server(), rootJob(), repo(), "run-1", WORK_DIR, SSH_HOME).exec();

        // All five borg invocations are sudo-prefixed...
        assertThat(exec).contains("sudo -n ").contains("borg info").contains("borg init")
            .contains("borg create").contains("borg prune").contains("borg compact");
        assertThat(countOf(exec, "sudo -n ")).isEqualTo(5);
        // ...and no borg call is left bare: every "borg " occurrence is immediately preceded by a sudo line.
        assertThat(countOf(exec, "borg info")).isEqualTo(1);
        for (String verb : List.of("borg info", "borg init", "borg create", "borg prune", "borg compact")) {
            assertThat(exec).contains(sudoPrefix() + verb);
        }
    }

    /**
     * TRAP (a): HOME. Plain sudo makes HOME {@code /root}, so anything that expands {@code ~} or reads
     * {@code $HOME} would look in root's home. HOME is therefore set explicitly to the SSH user's home — as a
     * literal absolute path resolved by the orchestration, never a {@code $HOME} the command would have to
     * expand. Note this does NOT fix ssh (see the BORG_RSH test: ssh ignores {@code $HOME} entirely); it is a
     * belt for every other tool in the chain.
     */
    @Test
    void detachedRun_whenBackupAsRoot_setsHomeToTheSshUsersHomeSoRootFindsTheKeyAndTheHostPin() {
        String exec = BorgCommand.detachedRun(server(), rootJob(), repo(), "run-1", WORK_DIR, SSH_HOME).exec();

        assertThat(exec).contains("sudo -n HOME='/home/ubuntu'");
        // Never a $HOME to expand — borg/ssh would look at the literal string, and sudo would have reset it.
        assertThat(exec).doesNotContain("HOME=$HOME").doesNotContain("HOME=\"$HOME\"");
        assertThat(exec).doesNotContain("/root/.ssh");
    }

    /**
     * TRAP (a2), THE ONE THAT ACTUALLY BIT: setting HOME is not enough, because <b>OpenSSH ignores
     * {@code $HOME}</b> — it resolves {@code ~} from {@code getpwuid(getuid())}, i.e. from the passwd entry of
     * the UID it is running as. Under sudo that is root, so root's ssh reads {@code /root/.ssh/known_hosts}
     * and {@code /root/.ssh/id_ed25519} no matter what HOME says. Neither exists, and host-key verification
     * runs BEFORE publickey auth, so every as-root run died with "Host key verification failed."
     *
     * <p>The fix is BORG_RSH: it names the SSH user's identity and known_hosts as absolute literals, so root's
     * ssh is pointed at the key {@link BorgCommand#ensureClientKeyPair} wrote and the host pin
     * {@link BorgCommand#pinHostKeys} wrote.
     */
    @Test
    void detachedRun_whenBackupAsRoot_pointsRootsSshAtTheSshUsersKeyAndPinViaBorgRsh() {
        String exec = BorgCommand.detachedRun(server(), rootJob(), repo(), "run-1", WORK_DIR, SSH_HOME).exec();

        assertThat(exec).contains("BORG_RSH='ssh -i /home/ubuntu/.ssh/id_ed25519"
            + " -o UserKnownHostsFile=/home/ubuntu/.ssh/known_hosts'");
        // Every one of the five sudoed borg invocations carries it — not just the create.
        assertThat(countOf(exec, "BORG_RSH=")).isEqualTo(5);
        // Root's ssh must never be left to fall back on /root/.ssh, where there is no key and no pin.
        assertThat(exec).doesNotContain("/root/.ssh");
    }

    /**
     * BORG_RSH carries the identity and the known_hosts file and NOTHING else: borg appends {@code -p <port>}
     * and the {@code user@host} itself, from the repo URL. Adding either here would either duplicate the port
     * flag or pin ssh to the wrong target when the repo URL changes.
     */
    @Test
    void detachedRun_whenBackupAsRoot_borgRshNamesNeitherThePortNorTheUserAtHost() {
        String exec = BorgCommand.detachedRun(server(), rootJob(), repo(), "run-1", WORK_DIR, SSH_HOME).exec();

        String rsh = between(exec, "BORG_RSH='", "'");
        assertThat(rsh).isEqualTo("ssh -i /home/ubuntu/.ssh/id_ed25519"
            + " -o UserKnownHostsFile=/home/ubuntu/.ssh/known_hosts");
        // borg supplies these from the repo URL — the RSH command must not.
        assertThat(rsh).doesNotContain("-p ").doesNotContain("8022")
            .doesNotContain("borg@").doesNotContain("192.168.3.3");
    }

    /**
     * The working path is left alone. A NON-root job runs as the SSH user, whose own home already resolves to
     * exactly the key and known_hosts ssh needs, so it gets no BORG_RSH at all — colina27 and apalveien5 keep
     * backing up byte-for-byte as they did.
     */
    @Test
    void detachedRun_withoutBackupAsRoot_rendersNoBorgRshBecauseTheSshUsersOwnHomeAlreadyResolves() {
        String exec = BorgCommand.detachedRun(server(), job(), repo(), "run-1", WORK_DIR, SSH_HOME).exec();

        assertThat(exec).doesNotContain("BORG_RSH");
    }

    /** The substring between the first {@code open} and the next {@code close} after it. */
    private static String between(String haystack, String open, String close) {
        int from = haystack.indexOf(open);
        assertThat(from).as("expected to find " + open).isGreaterThanOrEqualTo(0);
        int start = from + open.length();
        int end = haystack.indexOf(close, start);
        assertThat(end).as("expected a closing " + close).isGreaterThanOrEqualTo(0);
        return haystack.substring(start, end);
    }

    /**
     * TRAP (b): sudo resets the environment. The chain exports BORG_PASSCOMMAND into the shell, but sudo
     * discards it — root's borg would then have no passphrase and would hang or fail. So the passcommand is
     * passed explicitly on the sudo line (which is what needs the SETENV tag in sudoers).
     */
    @Test
    void detachedRun_whenBackupAsRoot_passesThePasscommandOnTheSudoLineBecauseSudoResetsTheEnv() {
        String exec = BorgCommand.detachedRun(server(), rootJob(), repo(), "run-1", WORK_DIR, SSH_HOME).exec();

        assertThat(exec).contains("BORG_PASSCOMMAND='cat /var/lib/vaier-backup/nas-borg.pass' borg");
        // Each of the five sudo lines carries it — not just the create.
        assertThat(countOf(exec, "BORG_PASSCOMMAND='cat /var/lib/vaier-backup/nas-borg.pass' borg")).isEqualTo(5);
        // Still no plaintext passphrase anywhere.
        assertThat(exec).doesNotContain("s3cr3t").doesNotContain("BORG_PASSPHRASE");
    }

    /**
     * TRAP (c): root's borg cache. If root's borg wrote into the SSH user's {@code ~/.cache/borg}, it would
     * leave root-owned cache files that break a later NON-root run of the same job (when the toggle is turned
     * back off). BORG_BASE_DIR isolates it under the work dir instead.
     */
    @Test
    void detachedRun_whenBackupAsRoot_isolatesRootsBorgCacheSoItCannotBreakALaterNonRootRun() {
        String exec = BorgCommand.detachedRun(server(), rootJob(), repo(), "run-1", WORK_DIR, SSH_HOME).exec();

        assertThat(exec).contains("BORG_BASE_DIR='/var/lib/vaier-backup/root'");
        assertThat(countOf(exec, "BORG_BASE_DIR='/var/lib/vaier-backup/root'")).isEqualTo(5);
    }

    /**
     * SECURITY. The sudoers grant permits the borg binary and nothing else, so the command must never sudo a
     * shell or an env wrapper: {@code sudo env …} and {@code sudo sh -c …} are each a trivial root shell for
     * anyone who can run them, which would turn this feature into a local privilege-escalation hole. Every
     * sudo invocation here runs {@code borg} directly, with env assignments passed to sudo itself.
     */
    @Test
    void detachedRun_whenBackupAsRoot_neverSudoesAShellOrAnEnvWrapper() {
        String exec = BorgCommand.detachedRun(server(), rootJob(), repo(), "run-1", WORK_DIR, SSH_HOME).exec();

        assertThat(exec)
            .doesNotContain("sudo env").doesNotContain("sudo -n env")
            .doesNotContain("sudo sh").doesNotContain("sudo -n sh")
            .doesNotContain("sudo bash").doesNotContain("sudo -n bash")
            .doesNotContain("sudo -E").doesNotContain("sudo -i").doesNotContain("sudo -s");
        // The only thing sudo ever runs is borg: after the env assignments, the command word is `borg`.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("sudo -n ([^;&|]*)").matcher(exec);
        int seen = 0;
        while (m.find()) {
            seen++;
            // Strip the VAR='…' assignments; whatever remains must start with the borg binary.
            String rest = m.group(1).replaceAll("^(?:[A-Z_]+='[^']*' )+", "");
            assertThat(rest).startsWith("borg ");
        }
        assertThat(seen).isEqualTo(5);
    }

    /**
     * The nesting survives the sudo prefix: the chain is still embedded in {@code nohup sh -c "…"}, still
     * writes its exit code and log to the per-run files, and still returns {@code STARTED} at once. A sudo
     * prefix that broke the {@code dqEmbed} escaping would corrupt the whole detached wrapper.
     */
    @Test
    void detachedRun_whenBackupAsRoot_keepsTheDetachedWrapperAndItsEscapingIntact() {
        String exec = BorgCommand.detachedRun(server(), rootJob(), repo(), "run-1", WORK_DIR, SSH_HOME).exec();

        assertThat(exec).startsWith("W=/var/lib/vaier-backup; mkdir -p \"$W\"; nohup sh -c \"");
        assertThat(exec).contains("; echo \\$? > $W/run-1.rc\" > \"$W/run-1.log\" 2>&1 & echo STARTED $!");
        // The single-quoted env values are literal inside the double-quoted sh -c, so they are NOT escaped;
        // only the structural \$? is. A stray backslash before a quote here would break the inner shell.
        assertThat(exec).doesNotContain("\\'");
    }

    /**
     * An as-root run with no resolved SSH home is REFUSED, never silently launched: without HOME, root's ssh
     * would look in /root for a key and a host pin that are not there, and the run would die at the NAS with a
     * misleading "Permission denied (publickey)". The orchestration must resolve the home first.
     */
    @Test
    void detachedRun_whenBackupAsRoot_refusesToRenderWithoutAnAbsoluteSshHome() {
        assertThatThrownBy(() -> BorgCommand.detachedRun(server(), rootJob(), repo(), "run-1", WORK_DIR, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("home");
        assertThatThrownBy(() -> BorgCommand.detachedRun(server(), rootJob(), repo(), "run-1", WORK_DIR, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            BorgCommand.detachedRun(server(), rootJob(), repo(), "run-1", WORK_DIR, "relative/home"))
            .isInstanceOf(IllegalArgumentException.class);
        // A job that is NOT as-root never needs a home, so it renders fine without one.
        assertThat(BorgCommand.detachedRun(server(), job(), repo(), "run-1", WORK_DIR, "").exec())
            .contains("borg create");
    }

    /**
     * Only the RUN chain escalates. Provisioning and browsing (init, list, the server-auth probe) stay as the
     * SSH user: they only talk to the NAS and read the user's own pass file, so root buys nothing there and
     * would only add another root-owned cache to clean up.
     */
    @Test
    void initListAndAuthProbeNeverRunUnderSudoEvenForAnAsRootJob() {
        assertThat(BorgCommand.init(server(), repo(), WORK_DIR).exec()).doesNotContain("sudo");
        assertThat(BorgCommand.listArchives(server(), repo(), WORK_DIR).exec()).doesNotContain("sudo");
        assertThat(BorgCommand.serverAuthProbe(server(), repo(), WORK_DIR)).doesNotContain("sudo");
    }

    /** The sudo prefix every borg invocation in an as-root chain carries. */
    private String sudoPrefix() {
        return "sudo -n HOME='/home/ubuntu' BORG_BASE_DIR='/var/lib/vaier-backup/root'"
            + " BORG_RSH='ssh -i /home/ubuntu/.ssh/id_ed25519"
            + " -o UserKnownHostsFile=/home/ubuntu/.ssh/known_hosts'"
            + " BORG_PASSCOMMAND='cat /var/lib/vaier-backup/nas-borg.pass' ";
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void createRendersRepoUrlArchiveTemplateCompressionAndExcludes() {
        BuiltCommand cmd = BorgCommand.create(server(), job(), repo(), WORK_DIR);

        assertThat(cmd.exec())
            .contains("borg create --json --stats --compression zstd,6 --exclude-caches")
            .contains("--exclude '*.tmp'")
            .contains("--exclude '/var/cache'")
            // The repo URL and the archive template are adjacent single-quoted tokens: 'URL'::'ARCHIVE'.
            .contains("'ssh://borg@192.168.3.3:8022/./colina'::'{hostname}-{now:%Y-%m-%dT%H:%M:%S}'")
            .contains("'/home/geir'")
            .contains("'/etc'");
        // Sources come after the repo::archive target.
        assertThat(cmd.exec().indexOf("::'{hostname}"))
            .isLessThan(cmd.exec().indexOf("'/home/geir'"));
    }

    @Test
    void createUsesPasscommandNotPlaintextPassphrase() {
        BuiltCommand cmd = BorgCommand.create(server(), job(), repo(), WORK_DIR);

        // The passphrase is NOT exported into the environment or placed on borg's argv: a passcommand
        // reads it from the provisioned 0600 file instead, so the secret never appears in the command.
        assertThat(cmd.exec()).contains(expectedPasscommand());
        assertThat(cmd.exec()).doesNotContain("BORG_PASSPHRASE").doesNotContain("s3cr3t");
        // No secret means nothing to mask: the redacted twin equals exec.
        assertThat(cmd.redacted()).isEqualTo(cmd.exec()).doesNotContain("s3cr3t");
    }

    @Test
    void detachedRunBackgroundsWithNohupWritesRcAndLogAndReturnsImmediately() {
        BuiltCommand cmd = BorgCommand.detachedRun(server(), job(), repo(), "run-1", WORK_DIR, SSH_HOME);

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
    void detachedRunInitialisesTheRepoIfAbsentBeforeCreating_soInitIsNeverAManualStep() {
        // A run must not fail with "Repository does not exist" just because nobody clicked Initialize.
        // Like ensurePassFile, the run ensures its prerequisite: borg info probes the repo, and only if it
        // is absent does borg init create it (repokey-blake2), then the create chain proceeds.
        BuiltCommand cmd = BorgCommand.detachedRun(server(), job(), repo(), "run-1", WORK_DIR, SSH_HOME);
        String exec = cmd.exec();
        String url = "'ssh://borg@192.168.3.3:8022/./colina'";

        // info-or-init guard, chained with && to the create so a genuine init failure aborts the run
        // (rather than being swallowed and then failing create with the same cryptic error).
        assertThat(exec).contains("borg info " + url + " > /dev/null 2>&1 || "
            + "borg init --encryption=repokey-blake2 --make-parent-dirs " + url);
        // The guard runs BEFORE create.
        assertThat(exec.indexOf("borg info " + url)).isLessThan(exec.indexOf("borg create --json"));
        assertThat(exec.indexOf("borg init")).isLessThan(exec.indexOf("borg create --json"));
        // Still no plaintext secret (init reads the passphrase from the same BORG_PASSCOMMAND file).
        assertThat(exec).doesNotContain("s3cr3t");
    }

    @Test
    void detachedRunChainsCreateThenPruneScopedByGlobThenCompact() {
        BuiltCommand cmd = BorgCommand.detachedRun(server(), job(), repo(), "run-1", WORK_DIR, SSH_HOME);
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
            + "'ssh://borg@192.168.3.3:8022/./colina'");
        // Compact targets the same repo URL (borg >= 1.2), single-quoted.
        assertThat(exec).contains("borg compact 'ssh://borg@192.168.3.3:8022/./colina'");
        // The passphrase never appears; the redacted twin equals exec.
        assertThat(exec).contains(expectedPasscommand()).doesNotContain("s3cr3t");
        assertThat(cmd.redacted()).isEqualTo(exec);
    }

    @Test
    void listArchivesBuildsBorgListJson() {
        BuiltCommand cmd = BorgCommand.listArchives(server(), repo(), WORK_DIR);
        String exec = cmd.exec();

        // Wrapped in sh -c with the passphrase supplied via a passcommand (from the 0600 file), then
        // borg list --json on the repo URL. Runs over the normal 20 s exec (listing is fast), not detached.
        assertThat(exec).startsWith("sh -c \"");
        assertThat(exec).contains(expectedPasscommand());
        assertThat(exec).contains("borg list --json 'ssh://borg@192.168.3.3:8022/./colina'");
        assertThat(exec.indexOf("export BORG_PASSCOMMAND")).isLessThan(exec.indexOf("borg list"));
        assertThat(exec).doesNotContain("BORG_PASSPHRASE").doesNotContain("s3cr3t");

        // No secret -> redacted twin equals exec.
        assertThat(cmd.redacted()).isEqualTo(exec).doesNotContain("s3cr3t");
    }

    @Test
    void repoUrlIsSingleQuotedInEveryCommandSite() {
        // Defense in depth: even though names/paths are validated at construction, every borg command
        // single-quotes the repo URL so a hand-edited YAML value can never break out of its argument.
        String q = "'ssh://borg@192.168.3.3:8022/./colina'";
        assertThat(BorgCommand.listArchives(server(), repo(), WORK_DIR).exec())
            .contains("borg list --json " + q);
        assertThat(BorgCommand.serverAuthProbe(server(), repo(), WORK_DIR))
            .contains("borg info " + q);
        assertThat(BorgCommand.init(server(), repo(), WORK_DIR).exec())
            .contains("--make-parent-dirs " + q);
        String run = BorgCommand.detachedRun(server(), job(), repo(), "run-1", WORK_DIR, SSH_HOME).exec();
        assertThat(run).contains("borg compact " + q);       // compact site
        assertThat(run).contains("--keep-monthly 6 " + q);   // prune site
        assertThat(run).contains(q + "::'{hostname}");        // create site: 'URL'::'ARCHIVE'
        // The synchronous create renders the same 'URL'::'ARCHIVE' shape.
        assertThat(BorgCommand.create(server(), job(), repo(), WORK_DIR).exec())
            .contains(q + "::'{hostname}");
    }

    @Test
    void aRepositoryOrServerNameCannotCarryAShellInjection() {
        // The guard is construction-time validation: a name that would inject a command (`a; rm -rf ~`, a
        // command substitution) is rejected outright, so no such value can ever reach a borg command line —
        // the URL/path single-quoting is the belt on top of that braces.
        assertThatThrownBy(() -> new BackupRepository("a; rm -rf ~", "nas-borg", null, "s3cr3t", false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackupRepository("a$(touch pwned)", "nas-borg", null, "s3cr3t", false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackupServer("a; rm -rf ~", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false))
            .isInstanceOf(IllegalArgumentException.class);
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
        String reach = BorgCommand.reachabilityProbe(server());
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
        BuiltCommand init = BorgCommand.init(server(), repo(), WORK_DIR);
        assertThat(init.exec())
            .contains(expectedPasscommand())
            .contains("borg init --encryption=repokey-blake2 --make-parent-dirs "
                + "'ssh://borg@192.168.3.3:8022/./colina'");
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

    // --- Slice 4: SSH key trust (closes #320) ---

    @Test
    void ensureClientKeyPairGeneratesOnlyIfAbsentThenPrintsPublicKey() {
        String cmd = BorgCommand.ensureClientKeyPair();

        // Keygen only when the private key is absent (idempotent, never overwrites an existing key), no
        // passphrase, quiet, ed25519, then the public key is printed for the orchestrator to read.
        assertThat(cmd)
            .contains("[ -f \"$HOME/.ssh/id_ed25519\" ]")
            .contains("ssh-keygen -t ed25519 -N '' -q -f \"$HOME/.ssh/id_ed25519\"")
            .contains("cat \"$HOME/.ssh/id_ed25519.pub\"");
        // The "||" short-circuit means keygen runs only when the "[ -f ... ]" test fails (key absent).
        assertThat(cmd.indexOf("[ -f")).isLessThan(cmd.indexOf("ssh-keygen"));
        assertThat(cmd).contains("||");
    }

    /** A blob long enough for keyMaterial to recognise it, reused across the authorize tests. */
    private static final String BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyDataHere";
    private static final String PUBKEY = "ssh-ed25519 " + BLOB + " geir@colina";

    @Test
    void restrictedKeyEntryRendersCommandRestrictAndPathsInGivenOrder() {
        // The restricted authorized_keys entry: a forced `borg serve` with one --restrict-to-path per repo,
        // `restrict` (no pty/forwarding/shell), then the public key — paths kept in the order supplied.
        String entry = BorgCommand.restrictedKeyEntry(PUBKEY, List.of("/a", "/b"));

        // Each path is single-quoted so a space/metacharacter in a path can never mis-scope the restriction.
        assertThat(entry).isEqualTo(
            "command=\"borg serve --restrict-to-path '/a' --restrict-to-path '/b'\",restrict " + PUBKEY);
    }

    @Test
    void keyMaterialExtractsTheBase64BlobFromBareNoCommentAndRestrictedLines() {
        // Identity is the key material (the base64 blob), stable across options and comment.
        assertThat(BorgCommand.keyMaterial(PUBKEY)).contains(BLOB);
        assertThat(BorgCommand.keyMaterial("ssh-ed25519 " + BLOB)).contains(BLOB);
        assertThat(BorgCommand.keyMaterial(
            "command=\"borg serve --restrict-to-path /a\",restrict ssh-ed25519 " + BLOB + " geir@colina"))
            .contains(BLOB);
        // Noise with no blob yields nothing.
        assertThat(BorgCommand.keyMaterial("Permission denied (publickey).")).isEmpty();
    }

    @Test
    void authorizeKeyWritesTheRestrictedEntryNewlineSafeBackedUpAndModeLocked() {
        String cmd = BorgCommand.authorizeKey(server(), PUBKEY, List.of("/home/borg/backups/colina27"));
        String entry = BorgCommand.restrictedKeyEntry(PUBKEY, List.of("/home/borg/backups/colina27"));

        // Writes to the server's authorized_keys under its data path.
        assertThat(cmd).contains("/volume1/docker/borg/ssh/authorized_keys");
        // Backs the file up before rewriting so existing keys can never be lost.
        assertThat(cmd).contains(".bak-vaier");
        // Normalises a missing trailing newline with awk 1 (the landmine: a naive >> concatenates keys).
        assertThat(cmd).contains("awk 1 ");
        // Idempotency now compares the exact FULL restricted entry, not a bare key. The entry now embeds
        // single quotes (around the restrict paths), so it is shell-quoted via the '\'' idiom in the command.
        assertThat(cmd).contains("grep -qxF " + sq(entry));
        // Locks the file to 0600.
        assertThat(cmd).contains("chmod 600");
        // Echoes a marker the orchestrator parses: ALREADY when present, ADDED when appended.
        assertThat(cmd).contains("ALREADY").contains("ADDED");
        // The restricted entry it writes appears verbatim (single-quoted) in the command.
        assertThat(cmd).contains(sq(entry));
    }

    @Test
    void authorizeKeyUpsertsByRemovingPriorLinesForTheSameKeyBeforeAppending() {
        // When the restriction set changes the LINE differs, so a naive append would leave TWO entries for
        // the same key — the older, broader one still granting access. The upsert first strips every line
        // carrying this key's material (bare or previously restricted), then appends the new one.
        String cmd = BorgCommand.authorizeKey(server(), PUBKEY, List.of("/home/borg/backups/colina27"));

        assertThat(cmd).contains("grep -vF '" + BLOB + "'");
        // The removal precedes the append of the new entry.
        assertThat(cmd.indexOf("grep -vF")).isLessThan(cmd.lastIndexOf("printf"));
    }

    @Test
    void authorizeKeySingleQuotesAnEntryEvenThoughItEmbedsADoubleQuote() {
        // The entry embeds `"` (in command="…") and the key may carry a stray single quote; both must be
        // single-quoted safely so nothing breaks out of its quoting. Do NOT touch singleQuote/dqEmbed.
        String key = "ssh-ed25519 AAAAB3NzaC1yc2EAAAADAQABtestblob o'brien@host";
        String cmd = BorgCommand.authorizeKey(server(), key, List.of("/home/borg/backups/x"));
        String entry = BorgCommand.restrictedKeyEntry(key, List.of("/home/borg/backups/x"));

        // The whole entry is single-quoted, embedded double quote and all, with the ' escaped via '\''.
        assertThat(cmd).contains("o'\\''brien@host");
        // The entry's raw substring only ever appears inside single quotes: no bare, unquoted occurrence.
        assertThat(cmd.replace("'" + entry.replace("'", "'\\''") + "'", "")).doesNotContain("restrict ssh-ed25519 AAAAB3");
    }

    @Test
    void authorizingTwiceWithAChangedRepoSetLeavesExactlyOneEntryForThatKey(@TempDir Path tmp)
        throws Exception {
        // The real regression: widen a machine's restriction (a repo was added) and the older, broader entry
        // must be replaced — not duplicated. Executes the generated command against a real file to prove it.
        org.junit.jupiter.api.Assumptions.assumeTrue(bashAvailable(), "bash required for this test");
        BackupServer srv = new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", tmp.toString(), false);

        runBash(BorgCommand.authorizeKey(srv, PUBKEY, List.of("/home/borg/backups/colina27")));
        runBash(BorgCommand.authorizeKey(srv, PUBKEY,
            List.of("/home/borg/backups/colina27", "/home/borg/backups/media")));

        List<String> lines = java.nio.file.Files.readAllLines(
            java.nio.file.Path.of(srv.authorizedKeysPath()));
        long forThisKey = lines.stream().filter(l -> l.contains(BLOB)).count();
        assertThat(forThisKey).isEqualTo(1);
        // The surviving entry is the widened one (paths single-quoted in the written authorized_keys line).
        assertThat(lines).anyMatch(l -> l.contains("--restrict-to-path '/home/borg/backups/media'"));
    }

    private static boolean bashAvailable() {
        try {
            return new ProcessBuilder("bash", "-c", "true").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Mirror of BorgCommand.singleQuote, for asserting the shell-quoted form of an entry in tests. */
    private static String sq(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void runBash(String command) throws Exception {
        Process p = new ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
    }

    @Test
    void wasAlreadyTrustedReadsTheMarker() {
        assertThat(BorgCommand.wasAlreadyTrusted("ALREADY")).isTrue();
        assertThat(BorgCommand.wasAlreadyTrusted("ALREADY\n")).isTrue();
        assertThat(BorgCommand.wasAlreadyTrusted("ADDED")).isFalse();
        assertThat(BorgCommand.wasAlreadyTrusted("ADDED\n")).isFalse();
        assertThat(BorgCommand.wasAlreadyTrusted("")).isFalse();
        assertThat(BorgCommand.wasAlreadyTrusted(null)).isFalse();
    }

    @Test
    void parsePublicKeyAcceptsWellFormedKeysAndRejectsNoise() {
        // Accepts a key with a comment.
        assertThat(BorgCommand.parsePublicKey(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyDataHere geir@colina\n"))
            .contains("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyDataHere geir@colina");
        // Accepts the no-comment form.
        assertThat(BorgCommand.parsePublicKey(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyDataHere"))
            .contains("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyDataHere");
        // Accepts an RSA key too.
        assertThat(BorgCommand.parsePublicKey(
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgSomeLongBase64Blob root@nas"))
            .isPresent();

        // Rejects empty / blank.
        assertThat(BorgCommand.parsePublicKey("")).isEmpty();
        assertThat(BorgCommand.parsePublicKey("   \n ")).isEmpty();
        assertThat(BorgCommand.parsePublicKey(null)).isEmpty();
        // Rejects an auth failure line masquerading as output.
        assertThat(BorgCommand.parsePublicKey("Permission denied (publickey).")).isEmpty();
        // Rejects a multi-line blob (an MOTD banner polluting the output must never reach authorized_keys).
        assertThat(BorgCommand.parsePublicKey(
            "Welcome to Ubuntu 22.04 LTS\nssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyDataHere geir@colina"))
            .isEmpty();
        // Rejects a plausible-looking but wrong-typed / short garbage line.
        assertThat(BorgCommand.parsePublicKey("not-a-key blob")).isEmpty();
        assertThat(BorgCommand.parsePublicKey("ssh-ed25519 short")).isEmpty();
    }

    // --- Slice 5: server-side auth probe (closes #320, survives the restricted forced-command key) ---

    @Test
    void serverAuthProbeRunsBorgInfoOnTheReposUrlViaThePasscommandNotSshBorgVersion() {
        // A restricted authorized_keys entry forces `borg serve` for EVERY session, so a `borg --version`
        // over ssh no longer runs version — it silently runs borg serve and reads EOF. The probe must instead
        // exercise the real path: `borg info <repoUrl>` from the client, unlocked via the same BORG_PASSCOMMAND
        // as a run/list, so it validates auth AND the per-repo restriction at once.
        String probe = BorgCommand.serverAuthProbe(server(), repo(), WORK_DIR);

        // The single quotes around the URL survive dqEmbed (it escapes $ " \ `, not '), exactly like the
        // already-single-quoted BORG_PASSCOMMAND export next to it.
        assertThat(probe).isEqualTo(
            "sh -c \"" + expectedPasscommand() + "; borg info 'ssh://borg@192.168.3.3:8022/./colina'\"");
        // Explicitly: it is NOT the retired ssh 'borg --version' probe.
        assertThat(probe).doesNotContain("BatchMode").doesNotContain("borg --version");
        assertThat(probe).contains("borg info").contains("BORG_PASSCOMMAND");
    }

    @Test
    void parseServerAuthMapsDeniedBootstrapSuccessAndConnectionFailures() {
        // AUTH_DENIED: the client key is not trusted (ssh-level or borg-tunnelled Remote:) — never ready.
        assertThat(BorgCommand.parseServerAuth("Permission denied (publickey,keyboard-interactive)."))
            .isEqualTo(BorgCommand.ServerAuthOutcome.AUTH_DENIED);
        assertThat(BorgCommand.parseServerAuth("Remote: Permission denied.\nConnection closed by remote host"))
            .isEqualTo(BorgCommand.ServerAuthOutcome.AUTH_DENIED);

        // AUTH_OK (bootstrap!): reaching "Repository does not exist" PROVES ssh auth succeeded and borg serve
        // ran — the repo just has not been `borg init`-ed yet. This is the fresh-network case.
        assertThat(BorgCommand.parseServerAuth(
            "Repository /home/borg/backups/colina does not exist."))
            .isEqualTo(BorgCommand.ServerAuthOutcome.AUTH_OK);
        assertThat(BorgCommand.parseServerAuth("Repository does not exist."))
            .isEqualTo(BorgCommand.ServerAuthOutcome.AUTH_OK);

        // AUTH_OK: a normal `borg info` body (the repo exists and unlocked) — auth obviously worked.
        assertThat(BorgCommand.parseServerAuth(
            "Repository ID: 3ac1f9e0deadbeef\nLocation: ssh://borg@192.168.3.3:8022/./colina\n"
                + "Encrypted: Yes (repokey BLAKE2b)\n"
                + "                       Original size      Compressed size    Deduplicated size\n"
                + "All archives:                1.20 GB              0.80 GB              0.40 GB"))
            .isEqualTo(BorgCommand.ServerAuthOutcome.AUTH_OK);

        // UNREACHABLE: a connection error, a timeout, a dropped tunnel — neither authenticated nor denied.
        assertThat(BorgCommand.parseServerAuth(
            "ssh: connect to host 192.168.3.3 port 8022: Connection refused"))
            .isEqualTo(BorgCommand.ServerAuthOutcome.UNREACHABLE);
        assertThat(BorgCommand.parseServerAuth("ssh: connect to host 192.168.3.3 port 8022: "
            + "Connection timed out")).isEqualTo(BorgCommand.ServerAuthOutcome.UNREACHABLE);
        assertThat(BorgCommand.parseServerAuth("ssh: connect to host 10.0.0.9 port 8022: No route to host"))
            .isEqualTo(BorgCommand.ServerAuthOutcome.UNREACHABLE);
        assertThat(BorgCommand.parseServerAuth("Connection closed by remote host"))
            .isEqualTo(BorgCommand.ServerAuthOutcome.UNREACHABLE);

        // Blank / null / garbage cannot be read as success -> UNREACHABLE, never an exception.
        assertThat(BorgCommand.parseServerAuth("")).isEqualTo(BorgCommand.ServerAuthOutcome.UNREACHABLE);
        assertThat(BorgCommand.parseServerAuth(null)).isEqualTo(BorgCommand.ServerAuthOutcome.UNREACHABLE);
        assertThat(BorgCommand.parseServerAuth("some unexpected banner"))
            .isEqualTo(BorgCommand.ServerAuthOutcome.UNREACHABLE);
    }

    // --- Slice 8: host-key pinning (no trust-on-first-use) ---

    /** Realistic public host keys as they appear under server_keys/ssh/*.pub. */
    private static final String ED25519 =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM89Zv3k9XqL5r7T2wF8bN0pQ1sR4uV6xY9zA2bC3dE root@borg";
    private static final String RSA =
        "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDf3kLmN0pQ1sR4uV6xY9zA2bC3dE5fG7hI8jK9lMroot root@borg";
    private static final String ECDSA =
        "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBExampleBlob root@borg";

    @Test
    void readServerHostKeysCatsThePublishedFileOnTheServerMachine() {
        assertThat(BorgCommand.readServerHostKeys(server()))
            .isEqualTo("cat '/volume1/docker/borg/ssh/host_keys.pub'");
    }

    @Test
    void parseHostKeysAcceptsRealKeysAndReturnsTypeKeyPairsWithoutComments() {
        // The comment field is dropped: only the `type key` pair a known_hosts entry needs is returned.
        assertThat(BorgCommand.parseHostKeys(ED25519 + "\n" + RSA + "\n" + ECDSA))
            .containsExactly(
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM89Zv3k9XqL5r7T2wF8bN0pQ1sR4uV6xY9zA2bC3dE",
                "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDf3kLmN0pQ1sR4uV6xY9zA2bC3dE5fG7hI8jK9lMroot",
                "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBExampleBlob");
    }

    @Test
    void parseHostKeysRejectsCommentsBlanksMotdNoiseAndPrivateKeyHeaders() {
        // MOTD banners, comment lines, blanks and a leaked private-key header must never reach known_hosts.
        String noisy = "# a comment line\n"
            + "\n"
            + "Welcome to Synology DSM 7.2 — unauthorized access is prohibited\n"
            + "-----BEGIN OPENSSH PRIVATE KEY-----\n"
            + ED25519 + "\n"
            + "not-a-key blob";
        // Only the one well-formed ed25519 line survives.
        assertThat(BorgCommand.parseHostKeys(noisy))
            .containsExactly(
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM89Zv3k9XqL5r7T2wF8bN0pQ1sR4uV6xY9zA2bC3dE");
        assertThat(BorgCommand.parseHostKeys(null)).isEmpty();
        assertThat(BorgCommand.parseHostKeys("")).isEmpty();
        assertThat(BorgCommand.parseHostKeys("-----BEGIN OPENSSH PRIVATE KEY-----")).isEmpty();
    }

    @Test
    void pinHostKeysAppendsBracketedHostPortEntriesBackedUpDedupedAndModeLocked() {
        List<String> keys = BorgCommand.parseHostKeys(ED25519 + "\n" + RSA);
        String cmd = BorgCommand.pinHostKeys(server(), keys);

        // Backs up known_hosts before rewriting, so a client's other pins are never lost.
        assertThat(cmd).contains("known_hosts").contains(".bak-vaier");
        // Removes ONLY this server's [host]:port entries first. Must use `ssh-keygen -R`, NOT `grep -vF`:
        // Ubuntu defaults to `HashKnownHosts yes`, so a stale entry is stored hashed and a substring grep
        // cannot see it — the stale key would survive and the client would keep refusing to connect.
        assertThat(cmd).contains("ssh-keygen -R '[192.168.3.3]:8022'");
        assertThat(cmd).doesNotContain("grep -vF");
        // Appends one entry per key in the [host]:port form (non-standard port => bracketed).
        assertThat(cmd).contains(
            "'[192.168.3.3]:8022 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM89Zv3k9XqL5r7T2wF8bN0pQ1sR4uV6xY9zA2bC3dE'");
        assertThat(cmd).contains(
            "'[192.168.3.3]:8022 ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDf3kLmN0pQ1sR4uV6xY9zA2bC3dE5fG7hI8jK9lMroot'");
        // Secures the dir and file, and echoes the count marker the orchestrator parses.
        assertThat(cmd).contains("mkdir -p \"$HOME/.ssh\"").contains("chmod 700 \"$HOME/.ssh\"");
        assertThat(cmd).contains("chmod 600");
        assertThat(cmd).contains("echo PINNED 2");
        // Idempotent: the removal precedes every append, so a second run replaces rather than duplicates.
        assertThat(cmd.indexOf("ssh-keygen -R")).isLessThan(cmd.indexOf("printf"));
    }

    @Test
    void pinHostKeysRemovesAStaleHASHEDEntry_whichASubstringGrepCannotSee() throws Exception {
        // Ubuntu ships `HashKnownHosts yes`, so a stale pin for this server is stored as an opaque |1|…
        // hash. A `grep -vF '[host]:port'` never matches it, the old key survives, and every borg run keeps
        // dying with REMOTE HOST IDENTIFICATION HAS CHANGED. `ssh-keygen -R` resolves hashed entries.
        java.nio.file.Path home = java.nio.file.Files.createTempDirectory("vaier-kh");
        java.nio.file.Path ssh = java.nio.file.Files.createDirectories(home.resolve(".ssh"));
        java.nio.file.Path kh = ssh.resolve("known_hosts");

        // Create a genuinely hashed stale entry for [192.168.3.3]:8022 using ssh-keygen itself.
        new ProcessBuilder("ssh-keygen", "-H", "-f", kh.toString()).start().waitFor();
        java.nio.file.Files.writeString(kh, "[192.168.3.3]:8022 ssh-ed25519 AAAASTALEKEYDATA stale\n");
        new ProcessBuilder("ssh-keygen", "-H", "-f", kh.toString()).inheritIO().start().waitFor();
        String hashed = java.nio.file.Files.readString(kh);
        assertThat(hashed).startsWith("|1|");                       // precondition: it really is hashed
        assertThat(hashed).doesNotContain("[192.168.3.3]:8022");    // and a substring grep is blind to it

        List<String> keys = BorgCommand.parseHostKeys(ED25519);
        String cmd = BorgCommand.pinHostKeys(server(), keys);
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        Process p = pb.start();
        p.getOutputStream().close();
        p.waitFor();

        String after = java.nio.file.Files.readString(kh);
        assertThat(after).doesNotContain("AAAASTALEKEYDATA");   // the stale hashed key is gone
        assertThat(after).contains("[192.168.3.3]:8022 ssh-ed25519");
    }

    @Test
    void pinHostKeysUsesTheBareHostFormWhenThePortIsTwentyTwo() {
        BackupServer std = new BackupServer("nas-borg", "NAS", "192.168.3.3", 22,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
        List<String> keys = BorgCommand.parseHostKeys(ED25519);
        String cmd = BorgCommand.pinHostKeys(std, keys);

        // A standard port 22 uses the bare `host` known_hosts form, never the bracketed one. Removal is by
        // ssh-keygen -R (host-anchored); an unanchored `grep -vF '192.168.3.3'` would also wipe this
        // client's [192.168.3.3]:8022 borg entry and any other host containing that substring.
        assertThat(cmd).contains("ssh-keygen -R '192.168.3.3'");
        assertThat(cmd).doesNotContain("grep -vF");
        assertThat(cmd).contains(
            "'192.168.3.3 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM89Zv3k9XqL5r7T2wF8bN0pQ1sR4uV6xY9zA2bC3dE'");
        assertThat(cmd).doesNotContain("[192.168.3.3]");
    }

    @Test
    void pinHostKeysIsIdempotentAgainstARealKnownHostsFile(@TempDir Path tmp) throws Exception {
        // Prove against a real file: pinning twice leaves exactly one entry per key type for this host:port.
        org.junit.jupiter.api.Assumptions.assumeTrue(bashAvailable(), "bash required for this test");
        Path home = tmp.resolve("home");
        java.nio.file.Files.createDirectories(home);
        List<String> keys = BorgCommand.parseHostKeys(ED25519 + "\n" + RSA);
        String cmd = BorgCommand.pinHostKeys(server(), keys);

        runBashWithHome(cmd, home);
        runBashWithHome(cmd, home);

        List<String> lines = java.nio.file.Files.readAllLines(home.resolve(".ssh/known_hosts"));
        long forThisHost = lines.stream().filter(l -> l.contains("[192.168.3.3]:8022")).count();
        assertThat(forThisHost).isEqualTo(2); // one ed25519 + one rsa, not four
        assertThat(lines).anyMatch(l -> l.contains("ssh-ed25519"));
        assertThat(lines).anyMatch(l -> l.contains("ssh-rsa"));
    }

    private static void runBashWithHome(String command, Path home) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command).redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
    }

    @Test
    void parsePinnedCountReadsTheMarker() {
        assertThat(BorgCommand.parsePinnedCount("PINNED 2")).contains(2);
        assertThat(BorgCommand.parsePinnedCount("PINNED 0\n")).contains(0);
        assertThat(BorgCommand.parsePinnedCount("some noise\nPINNED 3\n")).contains(3);
        assertThat(BorgCommand.parsePinnedCount("PINNED")).isEmpty();
        assertThat(BorgCommand.parsePinnedCount("")).isEmpty();
        assertThat(BorgCommand.parsePinnedCount(null)).isEmpty();
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
