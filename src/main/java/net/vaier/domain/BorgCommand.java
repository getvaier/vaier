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
    public static BuiltCommand create(BackupServer server, BackupJob job, BackupRepository repo, String workDir) {
        String cmd = exportPasscommand(repo, workDir) + buildCreateBody(server, job, repo, "borg", workDir);
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
    public static BuiltCommand detachedRun(BackupServer server, BackupJob job, BackupRepository repo,
                                           String runId, String workDir, String sshHome) {
        String borg = borgBinary(job, repo, workDir, sshHome);
        String chainBody = buildRunChain(server, job, repo, borg, workDir);
        String cmd = assembleDetached(repo, chainBody, runId, workDir);
        // The passphrase never appears in the run command (it comes from the provisioned pass file via
        // BORG_PASSCOMMAND), so there is nothing to mask: the redacted twin is identical to exec.
        return new BuiltCommand(cmd, cmd);
    }

    /**
     * How this job invokes borg on the machine: plain {@code borg} for a normal job, and — when the job opts in
     * to {@link BackupJob#backupAsRoot() Back up as root} — {@code sudo -n <env> borg}, so borg reads the
     * root-owned files (container volumes, {@code 0600} broker state) the SSH user would otherwise be silently
     * denied, leaving holes in the archive.
     *
     * <p>Four things sudo would otherwise break, all handled on this one line:
     * <ul>
     *   <li><b>SSH cannot find the key or the host pin — and {@code HOME} does not fix it.</b> The client's SSH
     *       key ({@link #ensureClientKeyPair}) and the server's pinned host key ({@link #pinHostKeys}) both live
     *       under the SSH <em>user's</em> home. Under sudo, ssh runs as root — and <b>OpenSSH ignores
     *       {@code $HOME}</b>: it resolves {@code ~} from {@code getpwuid(getuid())}, i.e. from the passwd entry
     *       of the UID it is running as. So root's ssh reads {@code /root/.ssh/}, which holds neither the key nor
     *       the pin, no matter what {@code HOME} is set to. Host-key verification runs <em>before</em> publickey
     *       auth, so the run does not even reach a key error — it dies with {@code Host key verification failed}.
     *       <b>{@code BORG_RSH}</b> is what actually fixes this: it names the identity and the known_hosts file
     *       as <b>absolute literals</b> under the SSH user's home, so root's ssh is pointed straight at them.
     *       It carries neither {@code -p <port>} nor the {@code user@host} — borg appends both itself from the
     *       repo URL.</li>
     *   <li><b>HOME.</b> Still set explicitly, to the <b>absolute literal</b> home the orchestration resolved
     *       (never a {@code $HOME} for the command to expand — same doctrine as the work dir; see
     *       {@code BackupWorkDirResolver}), so anything in the chain that <em>does</em> honour {@code $HOME}
     *       looks in the right place. It is no longer load-bearing for ssh (see above). The SSH home is required
     *       either way: it is what the {@code BORG_RSH} paths are built from, so an as-root render without an
     *       absolute one is refused outright rather than launched at the backup server to fail obscurely.</li>
     *   <li><b>The environment is reset.</b> The chain exports {@code BORG_PASSCOMMAND} into the shell, but sudo
     *       discards it, so root's borg would have no passphrase and would hang or fail. It is passed explicitly
     *       on the sudo line — which is exactly what the {@code SETENV:} tag in the sudoers drop-in
     *       ({@link BorgClientSetupScript}) permits, and which equally covers {@code BORG_RSH}.</li>
     *   <li><b>Root's borg cache.</b> Left alone, root's borg would write into the SSH user's
     *       {@code ~/.cache/borg} and leave root-owned files that break a later NON-root run of the same job
     *       (when the toggle is turned back off). {@code BORG_BASE_DIR} isolates it under
     *       {@code <workDir>/root}. The first as-root run therefore rebuilds the chunk cache — slower, but
     *       correct; dedup is content-addressed server-side, so nothing is stored twice.</li>
     * </ul>
     *
     * <p>A NON-root job gets none of this: it runs as the SSH user, whose own home already resolves to exactly
     * the key and known_hosts ssh needs, so plain {@code borg} is rendered and the working path is untouched.
     *
     * <p><b>Security.</b> Only the borg binary is ever sudoed — never {@code sudo env …}, never {@code sudo sh
     * -c …}, never a wildcard. Either of those is a trivial root shell for anyone who can run it, which would
     * turn this feature into a local privilege-escalation hole. The env assignments are given to <em>sudo</em>,
     * not to an env wrapper, and the sudoers Cmnd list names borg's absolute paths and nothing else.
     */
    private static String borgBinary(BackupJob job, BackupRepository repo, String workDir, String sshHome) {
        if (!job.backupAsRoot()) {
            return "borg";
        }
        if (sshHome == null || sshHome.isBlank() || !sshHome.startsWith("/")) {
            throw new IllegalArgumentException(
                "A back-up-as-root run needs the SSH user's absolute home; got: " + sshHome);
        }
        return "sudo -n"
            + " HOME=" + singleQuote(sshHome)
            + " BORG_BASE_DIR=" + singleQuote(rootBaseDir(workDir))
            + " BORG_RSH=" + singleQuote(rootRsh(sshHome))
            + " BORG_PASSCOMMAND=" + singleQuote("cat " + passFilePath(repo, workDir))
            + " borg";
    }

    /**
     * The {@code ssh} command root's borg must use: the SSH user's client identity and pinned known_hosts, both
     * as absolute literals under {@code sshHome} — because OpenSSH resolves {@code ~} from the running UID's
     * passwd entry and would otherwise read root's empty {@code /root/.ssh/}. No {@code -p} and no
     * {@code user@host}: borg appends those itself from the repo URL.
     */
    private static String rootRsh(String sshHome) {
        return "ssh -i " + sshHome + "/.ssh/id_ed25519"
            + " -o UserKnownHostsFile=" + sshHome + "/.ssh/known_hosts";
    }

    /** Root's isolated {@code BORG_BASE_DIR} for an as-root run: {@code <workDir>/root}. */
    private static String rootBaseDir(String workDir) {
        return workDir + "/root";
    }

    /**
     * The inner {@code create && prune && compact} chain the detached run executes, every borg invocation made
     * through {@code borg} — the binary (plain, or sudo-prefixed for a {@link BackupJob#backupAsRoot()} job)
     * decided by {@link #borgBinary}. The {@code &&} chaining means prune only runs on a successful create and
     * compact only after a successful prune, and the whole chain's exit code (via {@code echo \$?}) reflects the
     * first failure. Prune is scoped by {@code --glob-archives '{hostname}-*'} so a repository shared by several
     * jobs never cross-deletes another host's archives.
     */
    private static String buildRunChain(BackupServer server, BackupJob job, BackupRepository repo, String borg,
                                        String workDir) {
        return ensureInitializedBody(server, repo, borg)
            + " && " + buildCreateBody(server, job, repo, borg, workDir)
            + " && " + buildPruneBody(server, job, repo, borg)
            + " && " + buildCompactBody(server, repo, borg);
    }

    /**
     * Ensure the repository exists before a run creates into it, so {@code borg init} is never a manual
     * prerequisite the operator can forget (which surfaces as a cryptic "Repository does not exist" run
     * failure). Mirrors the ensure-pass-file guard: {@code borg info} probes the repo, and only when it is
     * absent does {@code borg init} create it. It is {@code &&}-chained to the create so a <em>genuine</em>
     * init failure aborts the run with init's own error, rather than being swallowed and re-failing create
     * with the same message. Init reads the passphrase from the same {@code BORG_PASSCOMMAND} file, so no
     * secret is added to the command.
     */
    private static String ensureInitializedBody(BackupServer server, BackupRepository repo, String borg) {
        String url = singleQuote(repo.borgRepoUrl(server));
        return borg + " info " + url + " > /dev/null 2>&1 || " + buildInitBody(server, repo, borg);
    }

    private static String buildInitBody(BackupServer server, BackupRepository repo, String borg) {
        return borg + " init --encryption=repokey-blake2 --make-parent-dirs "
            + singleQuote(repo.borgRepoUrl(server));
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
    public static BuiltCommand listArchives(BackupServer server, BackupRepository repo, String workDir) {
        String body = "borg list --json " + singleQuote(repo.borgRepoUrl(server));
        String cmd = "sh -c \"" + dqEmbed(exportPasscommand(repo, workDir)) + dqEmbed(body) + "\"";
        // No secret in the command (the passcommand reads the pass file) -> redacted equals exec.
        return new BuiltCommand(cmd, cmd);
    }

    /** The plain {@code borg --version} probe that reports the borg installed on a client host. */
    public static String versionProbe() {
        return "borg --version";
    }

    /**
     * Mount the archive named {@code archiveName} in {@code repo} as a read-only FUSE filesystem at
     * {@code mountpoint} — the mechanism behind browsing the past (Explorer slice D). Once mounted, the
     * archive is walked with the exact same SFTP code that walks the live tree; borg's {@code borgfs} mount
     * is {@code ro, user_id=1000}, so the kernel itself enforces "you can only paste into the present" and
     * Vaier never has to re-implement that invariant.
     *
     * <p><b>Idempotent.</b> A directory browse re-issues this on every archive it opens, so the command makes
     * the mountpoint dir and mounts only when it is not <em>already</em> a mount ({@code mountpoint -q}) —
     * an already-mounted archive is reused, never re-mounted into a "directory not empty"/"already mounted"
     * error. Unlike a {@code borg create}, {@code borg mount} daemonises and returns at once, so it fits the
     * 20 s SSH exec cap with none of {@link #detachedRun}'s nohup/poll machinery.
     *
     * <p>The passphrase reaches borg only through the {@code BORG_PASSCOMMAND} pass file — never argv or env —
     * exactly like {@link #listArchives}, wrapped in {@code sh -c "…"} so the export reaches borg. There is no
     * secret on the command line, so the redacted twin equals exec.
     */
    public static BuiltCommand mount(BackupServer server, BackupRepository repo, String archiveName,
                                     String mountpoint, String workDir) {
        String qMount = singleQuote(mountpoint);
        // REPO::ARCHIVE as two adjacent single-quoted tokens (the shell concatenates them into one argument
        // borg parses as REPO::ARCHIVE), so neither the URL nor the archive name can break out of its quoting.
        String source = singleQuote(repo.borgRepoUrl(server)) + "::" + singleQuote(archiveName);
        String body = "mkdir -p " + qMount + "; "
            + "mountpoint -q " + qMount + " && echo ALREADY_MOUNTED || "
            + "{ borg mount " + source + " " + qMount + " && echo MOUNTED; }";
        String cmd = "sh -c \"" + dqEmbed(exportPasscommand(repo, workDir)) + dqEmbed(body) + "\"";
        return new BuiltCommand(cmd, cmd);
    }

    /**
     * Release the archive mount at {@code mountpoint} and remove the now-empty mountpoint dir: {@code borg
     * umount} (its own wrapper over {@code fusermount -u}), then {@code rmdir}. Both are best-effort
     * ({@code 2>/dev/null}) so an already-unmounted or missing mountpoint is not an error — the idle sweep
     * must be able to run this without a stray failure when a mount vanished on its own.
     *
     * <p>The command then <em>re-probes</em> and reports the truth: {@code UNMOUNTED} only when the mountpoint
     * is genuinely no longer a mount, {@code STILL_MOUNTED} otherwise. A FUSE unmount routinely fails with
     * "Device or resource busy" while a handle is still open, and {@code borg umount}'s {@code 2>/dev/null}
     * hides that — the command would exit 0 regardless. Distinct post-check tokens let the sweep tell a real
     * release from a failed one, so it can keep the mount tracked and retry rather than forgetting it (a
     * forgotten mount is how a {@code borg mount} orphans and holds the repository lock forever). Read with
     * {@link #parseUnmounted}.
     */
    public static String umount(String mountpoint) {
        String qMount = singleQuote(mountpoint);
        return "borg umount " + qMount + " 2>/dev/null; "
            + "rmdir " + qMount + " 2>/dev/null; "
            + "mountpoint -q " + qMount + " && echo STILL_MOUNTED || echo UNMOUNTED";
    }

    /**
     * Read an {@link #umount} run's post-check: {@code true} only when it reported {@code UNMOUNTED} (the
     * mount is genuinely gone). {@code STILL_MOUNTED}, blank or null all read as "not released", so a failed
     * unmount keeps the mount tracked for a later retry. Whether a release actually took is a domain reading
     * of the command's output, so it lives here. Never throws.
     */
    public static boolean parseUnmounted(String stdout) {
        return stdout != null && stdout.contains("UNMOUNTED");
    }

    /**
     * A cheap probe of whether {@code mountpoint} is already an archive mount — used to short-circuit a
     * re-browse of the same archive to a single round trip, without a {@code borg list} to resolve the name.
     * Echoes {@code IS_MOUNTED}/{@code NOT_MOUNTED} (distinct tokens so one is never a substring of the other).
     */
    public static String isMounted(String mountpoint) {
        return "mountpoint -q " + singleQuote(mountpoint) + " && echo IS_MOUNTED || echo NOT_MOUNTED";
    }

    /** Read an {@link #isMounted} probe: {@code IS_MOUNTED} means the archive is already mounted. */
    public static boolean parseMounted(String stdout) {
        return stdout != null && stdout.contains("IS_MOUNTED");
    }

    /**
     * List the archive mounts that are actually live under a machine's {@code <workDir>/mounts} — one
     * {@code MOUNT:<mountpoint>} line per subdirectory that is a real mount, then a {@code MOUNTS_LISTED}
     * marker so an empty result is distinguishable from a failed run. This is how mount tracking is
     * reconciled with reality after a Vaier restart: the in-memory registry does not survive a restart, so a
     * {@code borg mount} left live on a host would otherwise never be swept. Asking the host what is mounted
     * lets Vaier adopt and reap those orphans (which hold the repository lock and block backups). A missing
     * mounts dir simply lists nothing. Read with {@link #parseArchiveMounts}.
     */
    public static String listArchiveMounts(String workDir) {
        String mountsDir = singleQuote(workDir + "/mounts");
        return "for m in " + mountsDir + "/*; do "
            + "mountpoint -q \"$m\" 2>/dev/null && echo \"MOUNT:$m\"; "
            + "done 2>/dev/null; echo MOUNTS_LISTED";
    }

    /**
     * Read a {@link #listArchiveMounts} run into the mountpoints it reported — every {@code MOUNT:<path>}
     * line, in order. Blank, null, or a bare {@code MOUNTS_LISTED} marker mean no live mounts. Which lines
     * name a mount is a domain reading of the command's output, so it lives here. Never throws.
     */
    public static java.util.List<String> parseArchiveMounts(String stdout) {
        if (stdout == null) {
            return java.util.List.of();
        }
        java.util.List<String> mounts = new java.util.ArrayList<>();
        for (String line : stdout.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("MOUNT:")) {
                mounts.add(trimmed.substring("MOUNT:".length()));
            }
        }
        return mounts;
    }

    /**
     * The three outcomes of the {@link #serverAuthProbe} run: {@code AUTH_OK} (the client authenticated and
     * borg serve ran — the repository exists <em>or</em> is merely not initialised yet), {@code AUTH_DENIED}
     * (the client's key is not trusted on the server), or {@code UNREACHABLE} (a connection error, timeout,
     * dropped tunnel, or output that reads as neither of the above). Deciding which of these a probe produced
     * is a domain rule, not orchestration, so it lives here.
     */
    public enum ServerAuthOutcome { AUTH_OK, AUTH_DENIED, UNREACHABLE }

    /**
     * The server-side auth probe, run <b>from the client host</b>: {@code borg info} on <em>this
     * repository's</em> URL, unlocked with the same {@code BORG_PASSCOMMAND} pass file as a run/list/init
     * (wrapped in {@code sh -c "…"} so the passcommand export reaches borg).
     *
     * <p>This replaces a former {@code ssh 'borg --version'} probe, which a <b>restricted, forced-command</b>
     * client key silently breaks: {@code command="borg serve …"} in {@code authorized_keys} discards the
     * client's requested command for every session, so {@code borg --version} never runs — borg serve reads
     * EOF instead and no version comes back. {@code borg info} exercises exactly the path that matters (can
     * this client talk to {@code borg serve} for its repo?), so it validates auth <em>and</em> the per-repo
     * restriction in one shot — strictly more meaningful than a version string. The server's version is not
     * asked here (the forced command makes it unknowable over SSH); it is derived elsewhere for a managed
     * server (see {@code BorgServerImage#borgVersion()}) and left unknown for an adopted one.
     */
    public static String serverAuthProbe(BackupServer server, BackupRepository repo, String workDir) {
        String body = "borg info " + singleQuote(repo.borgRepoUrl(server));
        return "sh -c \"" + dqEmbed(exportPasscommand(repo, workDir)) + dqEmbed(body) + "\"";
    }

    /**
     * Read a {@link #serverAuthProbe}'s combined output into a {@link ServerAuthOutcome}. Matching is
     * case-insensitive and keys off a few stable substrings, because borg's exact wording varies by version.
     * The decision order is the rule:
     * <ol>
     *   <li><b>{@code AUTH_DENIED}</b> — {@code Permission denied} / {@code publickey}. borg tunnels
     *       server-side stderr with a {@code Remote:} prefix, so a {@code Remote: … Permission denied} still
     *       reads as a denial.</li>
     *   <li><b>{@code AUTH_OK}</b> — {@code does not exist}: reaching borg's "repository not initialised yet"
     *       error <em>proves</em> ssh auth succeeded and {@code borg serve} ran. This is the bootstrap case
     *       on a fresh network, and it must read as ready-to-init, never as a failure.</li>
     *   <li><b>{@code UNREACHABLE}</b> — a connection error, timeout, dropped tunnel ({@code connection
     *       refused/closed/reset/timed out}, {@code no route to host}, {@code unreachable},
     *       {@code could not resolve}).</li>
     *   <li><b>{@code AUTH_OK}</b> — a successful {@code borg info} body (the repo exists and unlocked):
     *       recognised by its stable headers ({@code Repository ID}, {@code Encrypted:}, {@code Original
     *       size}).</li>
     *   <li>anything else (blank, garbled, an unknown banner) is {@code UNREACHABLE} — never optimistically
     *       read as success. Never throws.</li>
     * </ol>
     */
    public static ServerAuthOutcome parseServerAuth(String output) {
        String text = (output == null ? "" : output).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("permission denied") || text.contains("publickey")) {
            return ServerAuthOutcome.AUTH_DENIED;
        }
        // Reaching "repository … does not exist" proves auth + borg serve worked; the repo just isn't init'ed.
        if (text.contains("does not exist")) {
            return ServerAuthOutcome.AUTH_OK;
        }
        if (isConnectionFailure(text)) {
            return ServerAuthOutcome.UNREACHABLE;
        }
        if (isBorgInfoBody(text)) {
            return ServerAuthOutcome.AUTH_OK;
        }
        return ServerAuthOutcome.UNREACHABLE;
    }

    /** Stable connection-failure markers borg/ssh emit when the server host or tunnel is unreachable. */
    private static boolean isConnectionFailure(String lower) {
        return lower.contains("connection refused")
            || lower.contains("connection closed")
            || lower.contains("connection reset")
            || lower.contains("connection timed out")
            || lower.contains("timed out")
            || lower.contains("no route to host")
            || lower.contains("could not resolve")
            || lower.contains("name or service not known")
            || lower.contains("network is unreachable")
            || lower.contains("unreachable");
    }

    /** Stable headers a successful repo-level {@code borg info} always prints, across borg 1.2–1.4. */
    private static boolean isBorgInfoBody(String lower) {
        return lower.contains("repository id") || lower.contains("original size")
            || lower.contains("encrypted:");
    }

    /**
     * On the <b>client</b> host: generate the borg client's {@code ~/.ssh/id_ed25519} key pair only when it
     * is absent (idempotent — it never overwrites an existing key), with no passphrase, then print the
     * public key so the orchestration can read it and trust it on the {@link BackupServer}. The private key
     * never leaves the host; the public key it prints is <b>not</b> a secret, so this is a plain command
     * string (no {@link BuiltCommand} redaction needed).
     */
    public static String ensureClientKeyPair() {
        return "[ -f \"$HOME/.ssh/id_ed25519\" ] "
            + "|| ssh-keygen -t ed25519 -N '' -q -f \"$HOME/.ssh/id_ed25519\"; "
            + "cat \"$HOME/.ssh/id_ed25519.pub\"";
    }

    /**
     * Render the <b>restricted</b> {@code authorized_keys} entry for {@code publicKey}: a forced
     * {@code command="borg serve …"} with one {@code --restrict-to-path} per repository the client may
     * reach, plus the {@code restrict} option (no pty, no port/agent/X11 forwarding, no shell), then the
     * public key itself. A <em>bare</em> key would grant a full interactive shell as the borg user — enough
     * for any one compromised client to read and delete every other host's repositories — so Vaier never
     * writes one. The paths are kept in the order given; the orchestrator sorts and dedupes them first for a
     * deterministic line (the idempotency check compares this exact string).
     *
     * <p>Deciding what a trusted entry looks like is a domain rule, so it lives here rather than being
     * assembled by the orchestrator.
     */
    public static String restrictedKeyEntry(String publicKey, java.util.List<String> restrictPaths) {
        StringBuilder options = new StringBuilder("command=\"borg serve");
        for (String path : restrictPaths) {
            // Single-quote each path so a space or shell metacharacter in a (hand-edited) repository path
            // cannot mis-scope the confinement — `--restrict-to-path /a b` would otherwise restrict to `/a`
            // and leave `b` a stray argument. The forced command runs through the login shell, which strips
            // the quotes, so the effective restriction is unchanged for a normal path.
            options.append(" --restrict-to-path ").append(singleQuote(path));
        }
        options.append("\",restrict");
        return options + " " + publicKey;
    }

    /**
     * The key material of an {@code authorized_keys} line: the base64 blob that identifies a key regardless
     * of its options or comment (e.g. the {@code AAAA…} field of {@code ssh-ed25519 AAAA… geir@colina}).
     * Works on a bare key, a no-comment key, and a previously-restricted line ({@code command="…",restrict
     * ssh-ed25519 AAAA… …}) alike — the blob is the first whitespace-separated token that reads as a real
     * base64 blob. {@code Optional.empty()} when none is present. This is what an upsert keys off, so
     * extracting it is a domain rule.
     */
    public static Optional<String> keyMaterial(String publicKeyLine) {
        if (publicKeyLine == null) {
            return Optional.empty();
        }
        for (String token : publicKeyLine.strip().split("\\s+")) {
            if (token.matches("[A-Za-z0-9+/]{20,}={0,3}")) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    /**
     * On the <b>backup server's machine</b>: trust {@code publicKey} — confined to {@code restrictPaths} —
     * in the server's host-side {@code authorized_keys} (a mounted-volume path — see
     * {@link BackupServer#authorizedKeysPath()}), idempotently and newline-safely. What gets written is the
     * {@link #restrictedKeyEntry restricted entry}, never a bare key. A public key is not a secret, so this
     * is a plain command string — its exec form is safe to log verbatim (there is no redacted twin to keep).
     *
     * <p>Landmines learned on live hardware are handled here:
     * <ul>
     *   <li><b>Missing trailing newline.</b> The real {@code authorized_keys} ended with no {@code \n}; a
     *       naive {@code >>} would splice the new key onto the previous key's line, corrupting both.
     *       {@code awk 1} rewrites the file guaranteeing every line — including the last — is newline
     *       terminated before anything is appended.</li>
     *   <li><b>Upsert, not append.</b> When the restriction set changes (a repository was added for this
     *       machine) the <em>line</em> differs, so a naive append would leave two entries for the same key —
     *       the older, broader one still granting access. Identity is the {@link #keyMaterial key material},
     *       stable across options/comment: if the exact desired line is already present it is a no-op
     *       ({@code ALREADY}); otherwise <em>every</em> prior line carrying that key material (bare or
     *       previously restricted) is removed with {@code grep -vF} before the new entry is appended
     *       ({@code ADDED}). {@link #wasAlreadyTrusted(String)} reads that marker.</li>
     *   <li><b>Never clobber.</b> Existing keys (other hosts' and the borg server's own) survive untouched;
     *       the file is copied to a {@code .bak-vaier} backup before being rewritten.</li>
     * </ul>
     *
     * <p>Ownership: the work is staged in a {@code .tmp} written by the SSH user and moved into place with
     * {@code mv}, so the final file is owned by that same SSH user — exactly who Slice 3b's setup chowns
     * {@code authorized_keys} to, so the write succeeds without root. The whole entry is single-quoted via
     * {@link #singleQuote} (it contains spaces, {@code /}, {@code +} and an embedded {@code "}); it is never
     * interpolated unquoted.
     */
    public static String authorizeKey(BackupServer server, String publicKey,
                                      java.util.List<String> restrictPaths) {
        String ak = server.authorizedKeysPath();
        String entry = restrictedKeyEntry(publicKey, restrictPaths);
        String qEntry = singleQuote(entry);
        // Identify the key by its stable material so a changed restriction set replaces, never duplicates.
        String qMaterial = singleQuote(keyMaterial(publicKey).orElse(publicKey));
        return "AK=" + singleQuote(ak) + "; "
            + "mkdir -p \"$(dirname \"$AK\")\"; touch \"$AK\"; "
            + "cp \"$AK\" \"$AK.bak-vaier\"; "
            + "awk 1 \"$AK.bak-vaier\" > \"$AK.tmp\"; "
            + "if grep -qxF " + qEntry + " \"$AK.tmp\"; then echo ALREADY; "
            + "else grep -vF " + qMaterial + " \"$AK.tmp\" > \"$AK.tmp2\" || true; "
            + "printf '%s\\n' " + qEntry + " >> \"$AK.tmp2\"; mv \"$AK.tmp2\" \"$AK.tmp\"; echo ADDED; fi; "
            + "mv \"$AK.tmp\" \"$AK\"; chmod 600 \"$AK\"";
    }

    /**
     * Read an {@link #authorizeKey} run's marker: {@code true} when the client key was already present (the
     * append was a no-op), {@code false} when it was freshly added. Whether an authorize was idempotent is
     * a domain reading of the command's output, so it lives here rather than in the orchestrator.
     */
    public static boolean wasAlreadyTrusted(String stdout) {
        return stdout != null && stdout.contains("ALREADY");
    }

    /**
     * Validate and normalise the public-key output {@link #ensureClientKeyPair} prints on the client:
     * {@code Optional.of} a single plausible {@code ssh-<type> <base64> [comment]} line, else
     * {@code Optional.empty()}. This guards {@code authorized_keys} against a keygen error, a
     * {@code Permission denied} line, or MOTD banner noise slipping in — anything that is not exactly one
     * well-formed key line is rejected. Deciding what counts as a valid key is a domain rule, so it lives
     * here rather than in the orchestrator.
     */
    public static Optional<String> parsePublicKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String line = raw.strip();
        // Exactly one line: an MOTD banner or a keygen error printed alongside the key must never pass.
        if (line.isEmpty() || line.lines().count() != 1) {
            return Optional.empty();
        }
        String[] parts = line.split("\\s+");
        if (parts.length < 2) {
            return Optional.empty();
        }
        // A plausible key type (ssh-ed25519, ssh-rsa, ecdsa-sha2-*, sk-ssh-*) and a base64 blob of real
        // length — "not-a-key" / "Permission denied" / "short" are all rejected here.
        boolean typeOk = parts[0].matches("(ssh-|ecdsa-sha2-|sk-ssh-|sk-ecdsa-)[A-Za-z0-9@.-]+");
        boolean blobOk = parts[1].matches("[A-Za-z0-9+/]{20,}={0,3}");
        return (typeOk && blobOk) ? Optional.of(line) : Optional.empty();
    }

    /**
     * A bounded probe that checks whether the client host can reach the NAS borg port over the tunnel: a
     * 5-second {@code /dev/tcp} connect that echoes {@code NAS_OPEN} on success and {@code NAS_CLOSED}
     * otherwise, so a firewalled or down NAS never hangs the check.
     */
    public static String reachabilityProbe(BackupServer server) {
        return "timeout 5 bash -c 'cat </dev/null >/dev/tcp/" + server.host() + "/" + server.sshPort() + "'"
            + " && echo NAS_OPEN || echo NAS_CLOSED";
    }

    /** Read a {@link #reachabilityProbe} result: {@code NAS_OPEN} means the port is reachable. */
    public static boolean parseReachability(String stdout) {
        return stdout != null && stdout.strip().contains("NAS_OPEN");
    }

    /** A well-formed host-key line: a known key type and a base64 blob, optionally followed by a comment. */
    private static final java.util.regex.Pattern HOST_KEY_LINE = java.util.regex.Pattern.compile(
        "^(ssh-ed25519|ssh-rsa|ecdsa-sha2-\\S+) ([A-Za-z0-9+/]+=*)(\\s|$)");

    /**
     * On the <b>backup server's machine</b>: read the public host keys the setup script published (see
     * {@link BorgServerSetupScript} and {@link BackupServer#hostKeysPath()}) so Vaier can pin them on clients.
     * A plain {@code cat} of the {@code host_keys.pub} file — public keys, safe to log. When the file is
     * absent (an adopted or not-yet-provisioned server) {@code cat} exits non-zero and the orchestrator skips
     * pinning rather than failing the authorize.
     */
    public static String readServerHostKeys(BackupServer server) {
        return "cat " + singleQuote(server.hostKeysPath());
    }

    /**
     * Turn a {@link #readServerHostKeys} dump into the valid {@code type key} host-key pairs it contains,
     * dropping any comment field. Only lines that read as a real host key ({@code ssh-ed25519} / {@code
     * ssh-rsa} / {@code ecdsa-sha2-*} followed by a base64 blob) are kept — blank lines, {@code #} comments,
     * MOTD banners and a leaked private-key header are all rejected, so nothing but a real key can ever reach
     * a client's {@code known_hosts}. Deciding what counts as a valid host key is a domain rule, so it lives
     * here. Never throws.
     */
    public static java.util.List<String> parseHostKeys(String output) {
        if (output == null) {
            return java.util.List.of();
        }
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String line : output.split("\\R")) {
            java.util.regex.Matcher m = HOST_KEY_LINE.matcher(line.strip());
            if (m.find()) {
                keys.add(m.group(1) + " " + m.group(2));
            }
        }
        return keys;
    }

    /**
     * On the <b>client</b> host: pin {@code hostKeyLines} (the {@code type key} pairs from
     * {@link #parseHostKeys}) for {@code server} into {@code ~/.ssh/known_hosts}, so borg — which runs
     * non-interactively over SSH ({@code StrictHostKeyChecking=ask} with no tty) — has an authoritative pin
     * and never has to trust-on-first-use. Vaier is the trusted broker: it obtained these keys over its own
     * pinned, vault-authenticated channel to the server's machine, so this is a real pin, not TOFU.
     *
     * <p>The command is idempotent and known_hosts-safe:
     * <ul>
     *   <li>backs up {@code known_hosts} to {@code known_hosts.bak-vaier} before rewriting, so a client's
     *       other pins are never lost;</li>
     *   <li>removes <em>only</em> the existing entries for this exact {@code [host]:port} with {@code grep
     *       -vF} (explicit and testable — not {@code ssh-keygen -R}) <b>before</b> appending, so a second run
     *       replaces rather than duplicates;</li>
     *   <li>appends one {@code [host]:port type key} line per key;</li>
     *   <li>{@code mkdir -p ~/.ssh}, {@code chmod 700 ~/.ssh}, {@code chmod 600 known_hosts};</li>
     *   <li>echoes {@code PINNED <n>} so the orchestrator can confirm (see {@link #parsePinnedCount}).</li>
     * </ul>
     *
     * <p>The known_hosts key is {@code [host]:port} because the borg sshd uses a non-standard port; a
     * standard port 22 uses the bare {@code host} form instead (both are handled). Each entry is single-quoted
     * via {@link #singleQuote} so no character in a key can break out.
     */
    public static String pinHostKeys(BackupServer server, java.util.List<String> hostKeyLines) {
        String marker = knownHostsMarker(server);
        StringBuilder sb = new StringBuilder();
        sb.append("KH=\"$HOME/.ssh/known_hosts\"; ");
        sb.append("mkdir -p \"$HOME/.ssh\"; chmod 700 \"$HOME/.ssh\"; ");
        sb.append("touch \"$KH\"; cp \"$KH\" \"$KH.bak-vaier\"; ");
        // Strip every prior entry for THIS host first, so re-pinning replaces rather than duplicates.
        //
        // This MUST be `ssh-keygen -R`, not a `grep -vF` on the marker. OpenSSH defaults to
        // `HashKnownHosts yes` on Debian/Ubuntu, so an existing pin is stored as an opaque `|1|…` hash and a
        // substring grep cannot see it — the stale key would survive and the client would keep dying with
        // REMOTE HOST IDENTIFICATION HAS CHANGED. `ssh-keygen -R` resolves hashed entries, and is anchored
        // on the host rather than matching a bare substring anywhere in the line.
        sb.append("ssh-keygen -R ").append(singleQuote(marker)).append(" -f \"$KH\" >/dev/null 2>&1 || true; ");
        for (String line : hostKeyLines) {
            sb.append("printf '%s\\n' ").append(singleQuote(marker + " " + line)).append(" >> \"$KH\"; ");
        }
        sb.append("chmod 600 \"$KH\"; ");
        sb.append("echo PINNED ").append(hostKeyLines.size());
        return sb.toString();
    }

    /**
     * The {@code known_hosts} key for {@code server}: {@code [host]:port} for a non-standard port (the borg
     * sshd's), or the bare {@code host} form when the port is the standard 22. Which form to use is a domain
     * rule about how OpenSSH addresses a host, so it lives here.
     */
    private static String knownHostsMarker(BackupServer server) {
        return server.sshPort() == 22 ? server.host() : "[" + server.host() + "]:" + server.sshPort();
    }

    /**
     * Read a {@link #pinHostKeys} run's {@code PINNED <n>} marker into the number of keys pinned, or empty
     * when no well-formed marker is present. Whether a pin confirmed is a domain reading of the command's
     * output, so it lives here. Never throws.
     */
    public static Optional<Integer> parsePinnedCount(String stdout) {
        if (stdout == null) {
            return Optional.empty();
        }
        for (String line : stdout.strip().split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("PINNED")) {
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

    /**
     * The {@code borg init} that provisions a fresh repository: repokey-blake2 encryption with the parent
     * directories made, addressed by the repo URL. Like a run, the passphrase is supplied via
     * {@code BORG_PASSCOMMAND} from the provisioned pass file, so init never puts the secret on argv/env
     * either. No secret in the command -> the redacted twin equals exec.
     */
    public static BuiltCommand init(BackupServer server, BackupRepository repo, String workDir) {
        String cmd = exportPasscommand(repo, workDir) + buildInitBody(server, repo, "borg");
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
    private static String buildPruneBody(BackupServer server, BackupJob job, BackupRepository repo, String borg) {
        return borg + " prune --list --glob-archives " + singleQuote(job.archiveGlob())
            + " --keep-daily " + job.keepDaily()
            + " --keep-weekly " + job.keepWeekly()
            + " --keep-monthly " + job.keepMonthly()
            + " " + singleQuote(repo.borgRepoUrl(server));
    }

    /** The {@code borg compact} body that frees space after a prune (borg ≥ 1.2). */
    private static String buildCompactBody(BackupServer server, BackupRepository repo, String borg) {
        return borg + " compact " + singleQuote(repo.borgRepoUrl(server));
    }

    private static String buildCreateBody(BackupServer server, BackupJob job, BackupRepository repo, String borg,
                                          String workDir) {
        StringBuilder cmd = new StringBuilder(borg + " create --json --stats --compression ")
            .append(job.compression())
            .append(" --exclude-caches");
        // Keep borg's own state and Vaier's work dir out of every archive, so a run whose source paths
        // include a user home comes back a clean SUCCESS rather than a spurious WARNING:
        //   * <workDir> holds the 0600 pass file (the repo passphrase must NEVER be archived) and, for an
        //     as-root run, borg's BORG_BASE_DIR (its cache + security dir) under <workDir>/root;
        //   * '*/.config/borg' is a non-root run's borg security dir, whose per-repo "nonce" file changes
        //     mid-run — borg would otherwise report "file changed while we backed it up" and exit WARNING.
        // The pattern is single-quoted so the shell never globs it; borg applies it as an fnmatch exclude.
        cmd.append(" --exclude ").append(singleQuote(workDir));
        cmd.append(" --exclude ").append(singleQuote("*/.config/borg"));
        for (String exclude : job.excludes()) {
            cmd.append(" --exclude ").append(singleQuote(exclude));
        }
        // REPO::ARCHIVE as two adjacent single-quoted tokens: the shell concatenates 'url'::'archive' into
        // one argument, which borg parses as REPO::ARCHIVE — so the URL and the archive template are each
        // quoted independently and neither can break out.
        cmd.append(" ").append(singleQuote(repo.borgRepoUrl(server)))
            .append("::").append(singleQuote(job.archiveNameTemplate()));
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
