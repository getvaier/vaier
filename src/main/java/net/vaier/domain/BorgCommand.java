package net.vaier.domain;

import java.util.Optional;

/**
 * The single place in Vaier that knows the borg command line. Given a {@link BackupJob} and its
 * {@link BackupRepository}, it renders the exact shell string sent over SSH to push a backup, together
 * with a redacted twin safe to log. Keeping every borg flag here means the orchestration in {@code rest/}
 * and the services never grow borg knowledge.
 *
 * <p>Slice 1 covers the synchronous {@code create}. Slice 3 adds the detached run wrapper that survives
 * the 20 s SSH exec cap: {@link #detachedRun} backgrounds borg with {@code nohup}, returns
 * {@code STARTED <pid>} immediately, and writes the chain's exit code and output to persistent per-run
 * files under the {@code workDir} the orchestration passes in. {@link #pollStatus} then reads that result
 * file over the normal exec path and {@link #parsePoll} turns its output into a settled exit code (or
 * empty while running).
 *
 * <p>Every command that touches the work dir takes it as an explicit absolute path — never a {@code $HOME}
 * or {@code ~} the borg process would have to expand. The work dir is resolved once, in the {@code rest/}
 * orchestration (see {@code BackupWorkDirResolver}), because borg runs {@code BORG_PASSCOMMAND} without a
 * shell: a {@code $HOME} embedded in the passcommand would never expand, so the pass-file path this class
 * emits must already be a literal absolute string.
 *
 * <p>Slice 8 moves the passphrase off argv and env for good. Instead of exporting {@code BORG_PASSPHRASE}
 * into the environment, every run/list/init exports {@code BORG_PASSCOMMAND="cat <workDir>/<repo>.pass"}
 * — borg reads the secret from a provisioned {@code 0600} file at run time, so the plaintext never appears
 * in any command's argv or env-assignment. The only command that carries the plaintext is
 * {@link #writePassFile}/{@link #ensurePassFile}, whose {@link BuiltCommand#redacted()} twin masks it as
 * {@code ***} so it can still be logged safely.
 */
public final class BorgCommand {

    private static final String PASSPHRASE_MASK = "***";

    private BorgCommand() {
    }

    /**
     * A rendered borg command in two forms: {@code exec} is the real string to run over SSH (with the
     * live passphrase), {@code redacted} is identical but with the passphrase masked, for logging.
     */
    public record BuiltCommand(String exec, String redacted) {
    }

    /**
     * The synchronous {@code borg create} for {@code job} pushing into {@code repo}: json stats,
     * the job's compression, cache exclusion, each configured exclude, then the repo URL + archive-name
     * template and the source paths. The passphrase is exported into the environment ahead of the borg
     * invocation and masked in the redacted form.
     */
    public static BuiltCommand create(BackupJob job, BackupRepository repo, String workDir) {
        String cmd = exportPasscommand(repo, workDir) + buildCreateBody(job, repo);
        return new BuiltCommand(cmd, cmd);
    }

    /**
     * The detached {@code borg create} for {@code job} into {@code repo}, tagged {@code runId}, using
     * {@code workDir} for its persistent result/log files.
     *
     * <p>The returned {@code exec} sets up {@code $W=workDir}, then {@code nohup}s an inner {@code sh -c}
     * that exports the passphrase into its environment, runs the {@code create && prune && compact}
     * chain ({@link #buildRunChain}), and writes the chain's exit code to
     * {@code $W/<runId>.rc}. Its stdout/stderr go to {@code $W/<runId>.log}. Because the inner shell is
     * backgrounded and only {@code STARTED <pid>} is echoed, the outer command returns far inside the
     * 20 s SSH exec cap while borg keeps running.
     *
     * <p>Nested quoting: the inner command is embedded in {@code sh -c "…"}, so the passphrase and every
     * create argument are additionally escaped for that double-quoted context ({@link #dqEmbed}) — single
     * quotes alone do not protect a {@code $}/{@code "}/backtick from the outer shell. The structural
     * {@code $W} (expanded by the outer shell) and {@code \$?} (passed through to the inner shell so it
     * captures the chain's own exit code) are emitted raw.
     */
    public static BuiltCommand detachedRun(BackupJob job, BackupRepository repo, String runId, String workDir) {
        String chainBody = buildRunChain(job, repo);
        String cmd = assembleDetached(repo, chainBody, runId, workDir);
        // The passphrase never appears in the run command (it comes from the provisioned pass file via
        // BORG_PASSCOMMAND), so there is nothing to mask: the redacted twin is identical to exec.
        return new BuiltCommand(cmd, cmd);
    }

    /**
     * The inner {@code create && prune && compact} chain the detached run executes. The {@code &&}
     * chaining means prune only runs on a successful create and compact only after a successful prune,
     * and the whole chain's exit code (via {@code echo \$?}) reflects the first failure. Prune is scoped
     * by {@code --glob-archives '{hostname}-*'} so a repository shared by several jobs never cross-deletes
     * another host's archives.
     */
    private static String buildRunChain(BackupJob job, BackupRepository repo) {
        return buildCreateBody(job, repo)
            + " && " + buildPruneBody(job, repo)
            + " && " + buildCompactBody(repo);
    }

    private static String assembleDetached(BackupRepository repo, String chainBody,
                                           String runId, String workDir) {
        String inner = dqEmbed(exportPasscommand(repo, workDir))
            + dqEmbed(chainBody)
            + "; echo \\$? > $W/" + runId + ".rc";
        return "W=" + workDir + "; mkdir -p \"$W\"; "
            + "nohup sh -c \"" + inner + "\" > \"$W/" + runId + ".log\" 2>&1 "
            + "& echo STARTED $!";
    }

    /**
     * The {@code borg list --json} command for {@code repo}, wrapped in an {@code sh -c "…"} that exports
     * the passphrase into the environment first (never on borg's argv). Unlike {@link #detachedRun}, this
     * is <b>not</b> detached — listing is fast and runs inside the normal 20 s SSH exec cap. The redacted
     * twin masks the passphrase for logging.
     */
    public static BuiltCommand listArchives(BackupRepository repo, String workDir) {
        String body = "borg list --json " + repo.borgRepoUrl();
        String cmd = "sh -c \"" + dqEmbed(exportPasscommand(repo, workDir)) + dqEmbed(body) + "\"";
        // No secret in the command (the passcommand reads the pass file) -> redacted equals exec.
        return new BuiltCommand(cmd, cmd);
    }

    /** The plain {@code borg --version} probe that reports the borg installed on a client host. */
    public static String versionProbe() {
        return "borg --version";
    }

    /**
     * A bounded probe that checks whether the client host can reach the NAS borg port over the tunnel: a
     * 5-second {@code /dev/tcp} connect that echoes {@code NAS_OPEN} on success and {@code NAS_CLOSED}
     * otherwise, so a firewalled or down NAS never hangs the check.
     */
    public static String reachabilityProbe(BackupRepository repo) {
        return "timeout 5 bash -c 'cat </dev/null >/dev/tcp/" + repo.nasHost() + "/" + repo.sshPort() + "'"
            + " && echo NAS_OPEN || echo NAS_CLOSED";
    }

    /** Read a {@link #reachabilityProbe} result: {@code NAS_OPEN} means the port is reachable. */
    public static boolean parseReachability(String stdout) {
        return stdout != null && stdout.strip().contains("NAS_OPEN");
    }

    /**
     * The {@code borg init} that provisions a fresh repository: repokey-blake2 encryption with the parent
     * directories made, addressed by the repo URL. Like a run, the passphrase is supplied via
     * {@code BORG_PASSCOMMAND} from the provisioned pass file, so init never puts the secret on argv/env
     * either. No secret in the command -> the redacted twin equals exec.
     */
    public static BuiltCommand init(BackupRepository repo, String workDir) {
        String cmd = exportPasscommand(repo, workDir)
            + "borg init --encryption=repokey-blake2 --make-parent-dirs " + repo.borgRepoUrl();
        return new BuiltCommand(cmd, cmd);
    }

    /**
     * The single command that lands the plaintext passphrase on the host: a {@code 0600} file at
     * {@code <workDir>/<repo>.pass}, written under a tight {@code umask 077} so it is never briefly
     * world-readable. This is the only place the secret appears; {@link BuiltCommand#redacted()} masks it
     * for logging. Provisioning ({@code initRepo}) writes it unconditionally.
     */
    public static BuiltCommand writePassFile(BackupRepository repo, String workDir) {
        String exec = assembleWritePass(repo.passphrase(), repo, workDir, false);
        String redacted = assembleWritePass(PASSPHRASE_MASK, repo, workDir, false);
        return new BuiltCommand(exec, redacted);
    }

    /**
     * A write-if-absent variant of {@link #writePassFile}: the runner ensures the pass file exists before
     * launching a run, so a run never fails for a missing secret file, without rewriting it every night.
     * The plaintext is masked in the {@link BuiltCommand#redacted()} twin.
     */
    public static BuiltCommand ensurePassFile(BackupRepository repo, String workDir) {
        String exec = assembleWritePass(repo.passphrase(), repo, workDir, true);
        String redacted = assembleWritePass(PASSPHRASE_MASK, repo, workDir, true);
        return new BuiltCommand(exec, redacted);
    }

    private static String assembleWritePass(String passphrase, BackupRepository repo, String workDir,
                                            boolean ifAbsent) {
        String path = passFilePath(repo, workDir);
        // A top-level command over the SSH exec channel (the login shell runs it), so no wrapping sh -c is
        // needed — a nested quote would collide with the single-quoted passphrase. umask 077 makes the file
        // 0600 from creation, never briefly world-readable.
        String write = "umask 077; printf %s " + singleQuote(passphrase) + " > \"" + path + "\"";
        String setup = "mkdir -p \"" + workDir + "\"; ";
        if (ifAbsent) {
            return setup + "[ -f \"" + path + "\" ] || { " + write + "; }";
        }
        return setup + write;
    }

    /**
     * Whether a {@code borg init} that exited non-zero merely reported that the repository already exists —
     * a benign, idempotent outcome to treat as success rather than an error.
     */
    public static boolean isRepositoryAlreadyExists(String output) {
        return output != null && output.toLowerCase(java.util.Locale.ROOT).contains("already exists");
    }

    /**
     * The command that reports whether the detached run {@code runId} has finished: {@code DONE <code>}
     * once {@code $W/<runId>.rc} exists (with the borg chain's exit code), else {@code RUNNING}. Runs
     * inside the 20 s exec cap. {@code workDir} is expanded here (the command is wrapped in {@code sh -c
     * '…'} single quotes, so an unexpanded {@code $W} would not resolve).
     */
    public static String pollStatus(String runId, String workDir) {
        String rc = workDir + "/" + runId + ".rc";
        return "sh -c '[ -f \"" + rc + "\" ] && echo DONE $(cat \"" + rc + "\") || echo RUNNING'";
    }

    /** The command that returns the tail of a detached run's captured output, for the run summary. */
    public static String fetchLog(String runId, String workDir) {
        return "sh -c 'tail -c 4096 \"" + workDir + "/" + runId + ".log\"'";
    }

    /**
     * Parse {@link #pollStatus} output: {@code Optional.empty()} while the run is still {@code RUNNING}
     * (or the output is blank/unrecognised), the settled exit code when a {@code DONE <code>} line is
     * present. This RUNNING-vs-DONE decision is a domain rule, so it lives here rather than in the runner.
     */
    public static Optional<Integer> parsePoll(String stdout) {
        if (stdout == null) {
            return Optional.empty();
        }
        for (String line : stdout.strip().split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("DONE")) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        return Optional.of(Integer.parseInt(parts[1]));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The {@code <workDir>/<repo>.pass} file the provisioned passphrase is written to (0600). */
    private static String passFilePath(BackupRepository repo, String workDir) {
        return workDir + "/" + repo.name() + ".pass";
    }

    /**
     * The {@code export BORG_PASSCOMMAND='cat <workDir>/<repo>.pass'; } prefix a run/list/init uses so borg
     * reads the passphrase from the provisioned 0600 file rather than from argv or the environment. Single
     * quoting keeps a repository name with shell metacharacters from breaking out.
     */
    private static String exportPasscommand(BackupRepository repo, String workDir) {
        return "export BORG_PASSCOMMAND=" + singleQuote("cat " + passFilePath(repo, workDir)) + "; ";
    }

    /**
     * Escape {@code s} for embedding inside a double-quoted {@code sh -c "…"}: backslash, dollar, double
     * quote and backtick are the characters the outer shell would otherwise act on. Single quotes are
     * left untouched — they are literal inside double quotes and still quote the inner shell's arguments.
     */
    private static String dqEmbed(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '$' || c == '"' || c == '`') {
                b.append('\\');
            }
            b.append(c);
        }
        return b.toString();
    }

    /**
     * The {@code borg prune} body for {@code job} into {@code repo}: {@code --list} for a per-archive log,
     * scoped to just this job's archives with {@code --glob-archives '{hostname}-*'} so a shared repo
     * never cross-deletes another host's archives, then the job's daily/weekly/monthly retention and the
     * repo URL.
     */
    private static String buildPruneBody(BackupJob job, BackupRepository repo) {
        return "borg prune --list --glob-archives " + singleQuote(job.archiveGlob())
            + " --keep-daily " + job.keepDaily()
            + " --keep-weekly " + job.keepWeekly()
            + " --keep-monthly " + job.keepMonthly()
            + " " + repo.borgRepoUrl();
    }

    /** The {@code borg compact} body that frees space after a prune (borg ≥ 1.2). */
    private static String buildCompactBody(BackupRepository repo) {
        return "borg compact " + repo.borgRepoUrl();
    }

    private static String buildCreateBody(BackupJob job, BackupRepository repo) {
        StringBuilder cmd = new StringBuilder("borg create --json --stats --compression ")
            .append(job.compression())
            .append(" --exclude-caches");
        for (String exclude : job.excludes()) {
            cmd.append(" --exclude ").append(singleQuote(exclude));
        }
        cmd.append(" ").append(repo.borgRepoUrl()).append("::").append(singleQuote(job.archiveNameTemplate()));
        for (String source : job.sourcePaths()) {
            cmd.append(" ").append(singleQuote(source));
        }
        return cmd.toString();
    }

    /**
     * Wrap {@code value} in single quotes for the shell, escaping any embedded single quote with the
     * {@code '\''} idiom so the value cannot break out of its quoting.
     */
    private static String singleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
