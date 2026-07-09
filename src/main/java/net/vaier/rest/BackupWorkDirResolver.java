package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.domain.CommandResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the on-host directory a machine's borg runs use for their pass file and per-run
 * {@code .rc}/{@code .log} state. Vaier runs borg as the SSH user in {@code host-credentials.yml} (e.g.
 * {@code geir}, not root), who cannot create a directory under {@code /var/lib}; so the work dir is
 * {@code <home>/.vaier-backup}, resolved from the target host's {@code $HOME} over SSH, falling back to
 * {@code /tmp/vaier-backup} (always writable by any user) when resolution fails.
 *
 * <p><b>Why the absolute path is resolved here, not left as {@code $HOME} in the borg command.</b> The
 * run reads its passphrase via {@code BORG_PASSCOMMAND="cat <workDir>/<repo>.pass"}, and borg executes a
 * {@code BORG_PASSCOMMAND} <em>without a shell</em>. A literal {@code $HOME} embedded in that passcommand
 * would therefore never expand — borg would try to {@code cat} a file literally named {@code $HOME/...}
 * and fail. The path Vaier writes the pass file to and the path borg reads it from must be the exact same
 * absolute string, so {@code $HOME} is expanded once, here in the orchestration, before it ever reaches a
 * borg-consumed command. This keeps {@link net.vaier.domain.BorgCommand}'s command strings literal
 * absolute paths with no shell-quoting or expansion concerns.
 *
 * <p>A resolved home is cached per machine (the home does not move). A fallback is deliberately
 * <em>not</em> cached, so a transient probe failure never poisons the cache — a later call can still
 * resolve the real home once the host is reachable again. Resolution never throws.
 */
@Component
@Slf4j
public class BackupWorkDirResolver {

    /** Always writable by any SSH user; used when {@code $HOME} cannot be resolved. */
    static final String FALLBACK_WORK_DIR = "/tmp/vaier-backup";

    /** Prints {@code $HOME} with {@code printf %s} — no trailing newline — so the resolved path is exact. */
    static final String HOME_PROBE = "printf %s \"$HOME\"";

    private final RunRemoteCommandUseCase remoteCommand;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public BackupWorkDirResolver(RunRemoteCommandUseCase remoteCommand) {
        this.remoteCommand = remoteCommand;
    }

    /**
     * The work dir for {@code machineName}: a cached {@code <home>/.vaier-backup} once resolved, else a
     * fresh {@code $HOME} probe over SSH. Returns {@link #FALLBACK_WORK_DIR} (uncached) on any failure —
     * a timeout, a non-zero exit, a blank or non-absolute {@code $HOME}, or an SSH error — so a run is
     * never blocked and a blip never sticks.
     */
    public String workDirFor(String machineName) {
        String cached = cache.get(machineName);
        if (cached != null) {
            return cached;
        }
        try {
            CommandResult result = remoteCommand.run(machineName, HOME_PROBE);
            if (!result.timedOut() && result.exitCode() == 0 && result.stdout() != null) {
                String home = result.stdout().strip();
                if (!home.isBlank() && home.startsWith("/")) {
                    String dir = home + "/.vaier-backup";
                    cache.put(machineName, dir);
                    return dir;
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve $HOME on {} for backup work dir: {}",
                LogSafe.forLog(machineName), e.getMessage());
        }
        return FALLBACK_WORK_DIR;
    }
}
