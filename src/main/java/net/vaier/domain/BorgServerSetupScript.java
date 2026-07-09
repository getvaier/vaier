package net.vaier.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Generates the single bootstrap shell script a host runs to stand up a Vaier-managed borg
 * {@link BackupServer} from nothing. Vaier never creates containers on remote hosts — like
 * {@link PeerSetupScript} and {@link LanServerSetupScript}, it emits an idempotent bash script the host
 * runs (via a downloaded {@code setup.sh} or, where docker-over-SSH works, {@code POST …/provision}).
 *
 * <p>The script is pure, IO-free and safe to re-run: it {@code mkdir -p}s the data directories, writes a
 * <strong>pinned</strong> {@code docker-compose.yml} ({@link BorgServerImage#EXPECTED}), brings the
 * container up with {@code docker compose up -d}, and pre-creates a newline-terminated {@code 0600}
 * {@code authorized_keys} so a later key append (slice 4) can never concatenate onto a previous key.
 *
 * <p><strong>uid/gid are derived at run time, never baked.</strong> The borg image chowns its data to
 * {@code BORG_UID:BORG_GID} (it sets {@code ENSURE_BACKUP_PERMISSIONS=true}). Vaier later writes
 * {@code authorized_keys} <em>over SSH as the owner user</em> (e.g. {@code geir}, uid 1029 on the real
 * Synology — not 1000). So the script resolves {@code BORG_UID}/{@code BORG_GID} from the SSH owner's own
 * {@code id -u}/{@code id -g} on the host, and docker compose interpolates them into the container env.
 * Baking a fixed 1000/1000 would chown the data to the wrong user and silently break key trust.
 */
public final class BorgServerSetupScript {

    private BorgServerSetupScript() {}

    /**
     * Renders the bootstrap script for {@code server}, deriving the {@code BORG_UID}/{@code BORG_GID} the
     * borg container owns its data as from {@code ownerUser} <em>on the host at run time</em> (via
     * {@code id -u}/{@code id -g}), so the ownership always matches the SSH user Vaier authorizes keys as.
     * The owner is validated to exist before the compose runs. The container's sshd is mapped from the
     * server's {@link BackupServer#sshPort()} to the in-container {@code 22}, and every directory derives
     * from {@link BackupServer#serverDataPath()} — which must be set (a brand-new server always has one),
     * so a blank path is rejected loudly rather than producing a script that writes to {@code /}.
     */
    public static String generate(BackupServer server, String ownerUser) {
        String dataDir = server.serverDataPath();
        if (dataDir == null || dataDir.isBlank()) {
            throw new IllegalArgumentException(
                "Backup server '" + server.name() + "' has no serverDataPath; cannot render a setup script");
        }
        if (ownerUser == null || ownerUser.isBlank()) {
            throw new IllegalArgumentException(
                "Backup server '" + server.name() + "' has no owner user; cannot derive BORG_UID/BORG_GID");
        }
        int sshPort = server.sshPort();

        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("#\n");
        sb.append("# Vaier Backup server setup — idempotent, safe to re-run. Stands up a pinned borg server\n");
        sb.append("# (").append(BorgServerImage.EXPECTED).append(", borg 1.4.3) in Docker on this host.\n");
        sb.append("#\n");
        sb.append("set -euo pipefail\n\n");

        sb.append("if [ \"$(id -u)\" -ne 0 ]; then\n");
        sb.append("    echo \"ERROR: run this script as root (sudo bash $0)\" >&2\n");
        sb.append("    exit 2\n");
        sb.append("fi\n\n");

        // Derive the borg uid/gid from the SSH owner on THIS host, so the container chowns its data to the
        // exact user Vaier writes authorized_keys as. Never hardcode 1000 — the real NAS owner is uid 1029.
        sb.append("OWNER=\"").append(ownerUser).append("\"\n");
        sb.append("if ! id \"$OWNER\" >/dev/null 2>&1; then\n");
        sb.append("    echo \"ERROR: user '$OWNER' does not exist on this host — ")
            .append("cannot derive BORG_UID/BORG_GID\" >&2\n");
        sb.append("    exit 3\n");
        sb.append("fi\n");
        sb.append("BORG_UID=\"$(id -u \"$OWNER\")\"\n");
        sb.append("BORG_GID=\"$(id -g \"$OWNER\")\"\n");
        // Export so docker compose interpolates ${BORG_UID}/${BORG_GID} in the compose env from the shell.
        sb.append("export BORG_UID BORG_GID\n\n");

        sb.append("DATA_DIR=\"").append(dataDir).append("\"\n");
        sb.append("SSH_PORT=").append(sshPort).append("\n\n");

        // Synology (the headline target) symlinks docker into /usr/local/bin, which a non-interactive
        // `sudo bash` PATH omits. Widen it before probing for docker.
        sb.append("export PATH=\"$PATH:/usr/local/bin:/usr/bin\"\n\n");

        // Pick the compose front-end. Synology's Docker package ships ONLY the v1 `docker-compose` binary;
        // the v2 `docker compose` plugin is absent there. Prefer v2 where present, fall back to v1, and
        // fail loudly rather than silently skipping the container start.
        sb.append("if docker compose version >/dev/null 2>&1; then\n");
        sb.append("    COMPOSE=\"docker compose\"\n");
        sb.append("elif command -v docker-compose >/dev/null 2>&1; then\n");
        sb.append("    COMPOSE=\"docker-compose\"\n");
        sb.append("else\n");
        sb.append("    echo \"ERROR: neither 'docker compose' nor 'docker-compose' is available\" >&2\n");
        sb.append("    exit 4\n");
        sb.append("fi\n\n");

        sb.append("echo \"==> Preparing Backup server directories under ${DATA_DIR}\"\n");
        sb.append("mkdir -p \"${DATA_DIR}/ssh\" \"${DATA_DIR}/backups\" \"${DATA_DIR}/server_keys\"\n");
        // This script runs as root, but Vaier authorizes client keys over SSH *as the owner*, and that
        // write creates authorized_keys.tmp / .bak-vaier inside ssh/. A root-owned directory would make
        // those writes fail with permission denied, so hand the directory over too — not just the file.
        sb.append("chown \"$OWNER\" \"${DATA_DIR}/ssh\"\n\n");

        // authorized_keys must exist as an empty, newline-terminated 0600 file before any client key is
        // appended (slice 4). A file with no trailing newline makes a later '>>' concatenate the new key
        // onto the previous one and corrupt both — so pre-create and normalise it here.
        sb.append("AUTH_KEYS=\"${DATA_DIR}/ssh/authorized_keys\"\n");
        sb.append("if [ ! -f \"${AUTH_KEYS}\" ]; then\n");
        sb.append("    touch \"${AUTH_KEYS}\"\n");
        sb.append("fi\n");
        sb.append("# Append a trailing newline only when the file is non-empty and lacks one (command\n");
        sb.append("# substitution strips a trailing newline, so an empty result means the last byte was one).\n");
        sb.append("if [ -s \"${AUTH_KEYS}\" ] && [ -n \"$(tail -c1 \"${AUTH_KEYS}\")\" ]; then\n");
        sb.append("    printf '\\n' >> \"${AUTH_KEYS}\"\n");
        sb.append("fi\n");
        sb.append("chmod 600 \"${AUTH_KEYS}\"\n");
        // Vaier appends client keys to this file over SSH *as the owner*, not as root. Hand it over
        // explicitly rather than waiting for the container's ENSURE_BACKUP_PERMISSIONS to chown it — that
        // is a start-order race, and losing it silently breaks key trust.
        sb.append("chown \"$OWNER\" \"${AUTH_KEYS}\"\n\n");

        sb.append("echo \"==> Writing ${DATA_DIR}/docker-compose.yml\"\n");
        sb.append("cat > \"${DATA_DIR}/docker-compose.yml\" <<'COMPOSE'\n");
        sb.append(composeYaml(sshPort));
        sb.append("COMPOSE\n\n");

        // docker compose auto-loads a .env from the project directory. Without it the compose's
        // ${BORG_UID}/${BORG_GID} resolve only from this script's exported environment, so a later
        // hand-run `docker compose up -d` on the host would substitute empty values and the container
        // would chown its data to nothing. The .env makes the stack self-contained and re-runnable.
        sb.append("echo \"==> Writing ${DATA_DIR}/.env\"\n");
        sb.append("cat > \"${DATA_DIR}/.env\" <<ENVFILE\n");
        sb.append("BORG_UID=${BORG_UID}\n");
        sb.append("BORG_GID=${BORG_GID}\n");
        sb.append("ENVFILE\n\n");

        sb.append("echo \"==> Starting the Backup server (BORG_UID=${BORG_UID} BORG_GID=${BORG_GID})\"\n");
        sb.append("cd \"${DATA_DIR}\"\n");
        sb.append("$COMPOSE up -d\n\n");

        sb.append("echo \"==> Verifying the borg server answers on port ${SSH_PORT}\"\n");
        sb.append("waited=0\n");
        sb.append("until timeout 2 bash -c \"cat </dev/null >/dev/tcp/127.0.0.1/${SSH_PORT}\" 2>/dev/null; do\n");
        sb.append("    if [ ${waited} -ge 30 ]; then\n");
        sb.append("        echo \"WARNING: port ${SSH_PORT} did not open within 30s — check '$COMPOSE logs'\" >&2\n");
        sb.append("        break\n");
        sb.append("    fi\n");
        sb.append("    sleep 2\n");
        sb.append("    waited=$((waited + 2))\n");
        sb.append("done\n\n");

        // Publish the container's SSH host keys so each authorized client can PIN them (no trust-on-first-use).
        // The container generates fresh host keys under server_keys/ssh (root-owned, drwx------); this script
        // runs as root, so it can read them. Only the PUBLIC *.pub keys are published — safe to expose — and
        // the private ssh_host_*_key files are never touched. Vaier reads host_keys.pub over SSH and installs
        // the keys into each client's known_hosts.
        sb.append("echo \"==> Publishing the borg server host keys for clients to pin\"\n");
        sb.append("HOST_KEYS_SRC=\"${DATA_DIR}/server_keys/ssh\"\n");
        sb.append("waited=0\n");
        sb.append("until [ -f \"${HOST_KEYS_SRC}/ssh_host_ed25519_key.pub\" ]; do\n");
        sb.append("    if [ ${waited} -ge 30 ]; then\n");
        sb.append("        echo \"WARNING: host keys did not appear under ${HOST_KEYS_SRC} within 30s\" >&2\n");
        sb.append("        break\n");
        sb.append("    fi\n");
        sb.append("    sleep 2\n");
        sb.append("    waited=$((waited + 2))\n");
        sb.append("done\n");
        sb.append("if [ -f \"${HOST_KEYS_SRC}/ssh_host_ed25519_key.pub\" ]; then\n");
        // Public keys only: concatenate the *.pub files, hand the result to the SSH owner, make it 644.
        sb.append("    cat \"${HOST_KEYS_SRC}\"/*.pub > \"${DATA_DIR}/ssh/host_keys.pub\"\n");
        sb.append("    chown \"$OWNER\" \"${DATA_DIR}/ssh/host_keys.pub\"\n");
        sb.append("    chmod 644 \"${DATA_DIR}/ssh/host_keys.pub\"\n");
        sb.append("    echo \"    Host keys published to ${DATA_DIR}/ssh/host_keys.pub\"\n");
        sb.append("fi\n\n");

        sb.append("echo\n");
        sb.append("echo \"==> Vaier Backup server setup complete.\"\n");
        sb.append("echo \"    Authorize a client key next — Vaier appends it to ${AUTH_KEYS}.\"\n");
        return sb.toString();
    }

    /**
     * Wraps a rendered setup {@code script} in a detached launcher that survives
     * {@code MinaSshSessionAdapter}'s 20 s exec cap: {@code docker compose up -d} pulls a ~100 MB image, so
     * a synchronous run would be killed and reported failed even though the container comes up moments
     * later. Mirroring {@link BorgCommand#detachedRun}, it base64-encodes the script (so no character in
     * the script can break the command line), decodes it to {@code <workDir>/<runId>.sh}, makes it
     * executable, then runs it under {@code nohup} in the background — its exit code written to
     * {@code <workDir>/<runId>.rc} and its output to {@code <workDir>/<runId>.log}. Only
     * {@code STARTED <pid>} is echoed, so the exec returns at once while the pull continues. Status is then
     * polled over the normal exec path with the generic {@link BorgCommand#pollStatus},
     * {@link BorgCommand#parsePoll} and {@link BorgCommand#fetchLog} over the same rc/log files.
     *
     * <p>Quoting: {@code $W} (the work dir) is expanded by the outer shell; inside the backgrounded
     * {@code sh -c "…"} the script path is double-quoted and {@code \$?} is passed through so the inner
     * shell captures the script's own exit code.
     */
    public static String detachedLaunch(String script, String runId, String workDir) {
        String b64 = base64(script);
        String scriptPath = "$W/" + runId + ".sh";
        return "W=" + workDir + "; mkdir -p \"$W\"; "
            + "printf %s '" + b64 + "' | base64 -d > \"" + scriptPath + "\"; "
            + "chmod +x \"" + scriptPath + "\"; "
            + "nohup sh -c \"\\\"" + scriptPath + "\\\"; echo \\$? > \\\"$W/" + runId + ".rc\\\"\" "
            + "> \"$W/" + runId + ".log\" 2>&1 & echo STARTED $!";
    }

    /**
     * Writes a rendered setup {@code script} onto the host at {@code path} for the operator to run — the path
     * taken when Vaier can SSH the machine but cannot drive its docker socket as a non-root user (the Synology
     * NAS), so provisioning degrades to "run this one command". It never curls anything: the setup endpoint
     * sits behind admin auth, so Vaier stages the bytes itself. Like {@link #detachedLaunch}, the script is
     * base64-encoded (so no character in it — quotes, {@code $}, heredocs — can break the command line),
     * {@code mkdir -p}s the parent, decodes it to {@code path}, makes it executable, then echoes
     * {@code STAGED <path>} as a machine-readable confirmation ({@link #parseStagedPath}). {@code path} must be
     * a location the SSH user can write (the resolved work dir under {@code $HOME}).
     */
    public static String stageScript(String script, String path) {
        String b64 = base64(script);
        return "mkdir -p \"$(dirname '" + path + "')\"; "
            + "printf %s '" + b64 + "' | base64 -d > '" + path + "'; "
            + "chmod +x '" + path + "'; "
            + "echo STAGED '" + path + "'";
    }

    /**
     * Read the {@code STAGED <path>} confirmation {@link #stageScript} echoes: the absolute path the script
     * was written to, or empty when no confirmation line is present (a staging failure). The shell strips the
     * single quotes, but a quoted path is tolerated too. Never throws.
     */
    public static Optional<String> parseStagedPath(String stdout) {
        if (stdout == null) {
            return Optional.empty();
        }
        for (String line : stdout.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("STAGED ")) {
                String path = trimmed.substring("STAGED ".length()).strip();
                if (path.length() >= 2 && path.startsWith("'") && path.endsWith("'")) {
                    path = path.substring(1, path.length() - 1);
                }
                if (!path.isBlank()) {
                    return Optional.of(path);
                }
            }
        }
        return Optional.empty();
    }

    /** Base64-encode a script so no character in it can break the shell command line it is embedded in. */
    private static String base64(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The {@code docker-compose.yml} body, emitted inside a quoted heredoc so no shell expansion touches
     * the file's contents. The port is baked in by Java; {@code ${BORG_UID}}/{@code ${BORG_GID}} are left
     * as literal compose variables that <em>docker compose</em> interpolates from the exported shell
     * environment (resolved from the SSH owner in {@link #generate}), so the ownership matches the SSH
     * user Vaier authorizes keys as. {@code BORG_SERVE_ADDITIONAL_ARGS} is intentionally left empty — it is
     * the future append-only lever (set it to {@code --append-only} to restrict clients), kept empty in V1
     * so nightly prune/compact still work.
     */
    private static String composeYaml(int sshPort) {
        return "services:\n"
            + "  borg-server:\n"
            + "    image: " + BorgServerImage.EXPECTED + "\n"
            + "    container_name: vaier-borg-server\n"
            + "    restart: unless-stopped\n"
            + "    ports:\n"
            + "      - \"" + sshPort + ":22\"\n"
            + "    volumes:\n"
            + "      - ./ssh:/home/borg/.ssh\n"
            + "      - ./backups:/home/borg/backups\n"
            + "      - ./server_keys:/var/lib/docker-borg\n"
            + "    environment:\n"
            + "      - BORG_UID=${BORG_UID}\n"
            + "      - BORG_GID=${BORG_GID}\n"
            + "      - ENSURE_BACKUP_PERMISSIONS=true\n"
            + "      # Append-only lever (future): set to '--append-only' to restrict clients to append-only\n"
            + "      # writes. Left empty in V1 so nightly prune/compact still work.\n"
            + "      - BORG_SERVE_ADDITIONAL_ARGS=\n";
    }

    /**
     * A bounded probe of whether this SSH user can actually drive docker on a host — echoes
     * {@code DOCKER_OK} only when the CLI exists, <em>the daemon is reachable</em>, and some compose
     * front-end is available; otherwise {@code DOCKER_ABSENT}. Used by provisioning to degrade gracefully
     * to "run the setup script" instead of attempting a provision that cannot work.
     *
     * <p>Three real-world details this must survive, all observed on the Synology NAS: docker lives in
     * {@code /usr/local/bin}, which a non-interactive SSH {@code PATH} omits; the non-root SSH user is
     * denied on {@code /var/run/docker.sock}, so mere CLI presence proves nothing (hence {@code docker ps},
     * which touches the daemon); and only the v1 {@code docker-compose} binary exists there, not the v2
     * {@code docker compose} plugin.
     */
    public static String dockerAvailabilityProbe() {
        return "export PATH=\"$PATH:/usr/local/bin:/usr/bin\"; "
            + "command -v docker >/dev/null 2>&1 "
            + "&& docker ps >/dev/null 2>&1 "
            + "&& { docker compose version >/dev/null 2>&1 || command -v docker-compose >/dev/null 2>&1; } "
            + "&& echo DOCKER_OK || echo DOCKER_ABSENT";
    }

    /** Read a {@link #dockerAvailabilityProbe} result: {@code DOCKER_OK} means docker-over-SSH is usable. */
    public static boolean parseDockerAvailable(String stdout) {
        return stdout != null && stdout.strip().contains("DOCKER_OK");
    }
}
