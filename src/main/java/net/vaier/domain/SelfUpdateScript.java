package net.vaier.domain;

import java.util.List;

/**
 * The script Vaier runs on its own host to replace itself, and the command that launches it.
 *
 * <p>Everything about its shape follows from one fact: <b>the process that asks for the update dies in the
 * middle of it</b>. A container cannot recreate itself — the instant {@code docker compose up -d} replaces
 * it, whatever was driving the update is gone, along with any in-memory record that it was happening and any
 * connection it might have reported to. So the work is handed to the host and detached, and it has to be able
 * to finish, judge itself, undo itself and leave an account, with nobody listening.
 *
 * <p><b>The rollback is the feature.</b> If a bad image comes up, the thing that is down is the thing an
 * operator would use to fix it — Vaier is the fleet's control plane, its web terminal and its file browser.
 * So the script pins what was running <em>before</em> it touches anything, by digest and not by tag (the tag
 * moves under it during the pull, so rolling "back" to {@code :latest} would roll forward to the broken image
 * again), and puts that back if the new one does not answer. Since #343 the rollback target also holds the
 * runtime files the stack was started from, because the update now brings those to the new release too.
 *
 * <p>Rendering the script here rather than in a service follows {@link BorgServerSetupScript} and
 * {@link BorgClientSetupScript}: what the host is told to do is a domain rule, and the runner only carries it.
 */
public final class SelfUpdateScript {

    /**
     * Where the script leaves its account of what happened. Vaier is restarting while it runs, so there is no
     * in-memory state to settle against — the outcome has to outlive both the old process and the script, and
     * be waiting on disk when the new one boots.
     *
     * <p>Under the SSH user's own home, deliberately, and it is a shell expression rather than a path: a
     * fixed {@code /var/lib/...} needs root to create, and Vaier does not always have root on the host it
     * updates. It would not have failed loudly either — the update would have run correctly and simply left
     * no account, so a rollback would have been silent and Settings would have gone on reporting that nothing
     * had ever been updated. (The same trap as {@code /var/lib/vaier-backup} on a non-root host.) Both the
     * script and the read run as the same SSH user, so both resolve it to the same place.
     *
     * <p>Still spelled "upgrade": the writer is the <em>old</em> Vaier's script and the reader is the new
     * one, so renaming the path would lose the account of the very update that renamed it.
     */
    public static final String RESULT_FILE = "$HOME/.vaier-upgrade/last-upgrade";

    /**
     * The runtime files: the compose file and the committed asset trees it bind-mounts — install.sh's
     * {@code RUNTIME_PATHS}, which InstallScriptCoverageTest holds this equal to. The update backs these up
     * so a rollback can put them back; install.sh itself is what fetches them.
     */
    public static final List<String> RUNTIME_PATHS =
        List.of("docker-compose.yml", "offline", "oauth2/templates", "dex/themes", "crowdsec/acquis.d");

    /** The image label the release build stamps with the commit it was built from. */
    public static final String REVISION_LABEL = "org.opencontainers.image.revision";

    /** Where install.sh and the release tarball are fetched from — GitHub, not the image's registry. */
    private static final String SOURCE_REPOSITORY = "getvaier/vaier";

    /** The compose service that shares wireguard's network namespace and dies when wireguard is recreated. */
    private static final String MASQUERADE_SERVICE = "wireguard-masquerade";

    /** How long to wait for the replacement to answer before deciding it will not. */
    public static final int DEFAULT_HEALTH_TIMEOUT_SECONDS = 120;

    private SelfUpdateScript() {}

    /**
     * Render the update script. It pins the running image and backs up the {@link #RUNTIME_PATHS}, pulls,
     * runs install.sh at the commit the new image names in {@link #REVISION_LABEL} (#343), brings the whole
     * project up, waits (bounded) for the new container to answer on the endpoint that reports Vaier's version
     * — so "it came up" and "it is the build we asked for" are one check — and on silence puts the pinned
     * image and the backed-up files back. Every path out writes a single line to {@link #RESULT_FILE}:
     * {@code UPGRADED}, {@code ROLLED_BACK} or {@code FAILED}, with the run id so a stale result from an
     * earlier update is never mistaken for this one's. Those words are the on-disk protocol and are frozen —
     * see {@link #RESULT_FILE}.
     */
    public static String generate(String composeDir, String service, String runId, int healthTimeoutSeconds) {
        String dir = quote(composeDir);
        String svc = quote(service);
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("#\n");
        sb.append("# Vaier self-update. Runs detached on the host, because it replaces the container that\n");
        sb.append("# asked for it. Brings the whole stack to the commit the new image was built from, and puts\n");
        sb.append("# the previous image and runtime files back if the new Vaier does not answer.\n");
        sb.append("#\n");
        // Deliberately not `set -e`: a failing step must reach the result file, not abort the script and
        // leave the host with no account of what happened.
        sb.append("set -uo pipefail\n\n");

        sb.append("RUN_ID=").append(quote(runId)).append("\n");
        // Double-quoted, not single: $HOME has to expand.
        sb.append("RESULT=\"").append(RESULT_FILE).append("\"\n");
        sb.append("STATE=\"$(dirname \"$RESULT\")\"\n");
        sb.append("mkdir -p \"$STATE\"\n");
        sb.append("BACKUP=\"$STATE/stack-$RUN_ID\"\n");
        sb.append("INSTALLER=\"$STATE/install-$RUN_ID.sh\"\n");
        sb.append("LOG=\"$STATE/last-update.log\"\n");
        sb.append(": > \"$LOG\"\n");
        sb.append("trap 'rm -rf \"$BACKUP\" \"$INSTALLER\"' EXIT\n\n");

        sb.append("say() { echo \"$RUN_ID $1 $(date -u +%Y-%m-%dT%H:%M:%SZ) ${2:-}\" > \"$RESULT\"; }\n\n");

        sb.append("cd ").append(dir).append(" || { say FAILED 'no-compose-dir'; exit 2; }\n\n");

        // Pin the running image by digest BEFORE the pull. A tag is not a rollback target: `:latest` means
        // something different the moment the pull lands.
        sb.append("CID=\"$(docker compose ps -q ").append(svc).append(" 2>/dev/null)\"\n");
        sb.append("PREVIOUS_IMAGE=\"$(docker inspect --format '{{index .RepoDigests 0}}' \"$CID\" "
            + "2>/dev/null)\"\n");
        sb.append("if [ -z \"$PREVIOUS_IMAGE\" ]; then\n");
        sb.append("    PREVIOUS_IMAGE=\"$(docker inspect --format '{{.Config.Image}}' \"$CID\" 2>/dev/null)\"\n");
        sb.append("fi\n\n");

        // The runtime files go into the rollback target beside the image, and before anything moves.
        sb.append("RUNTIME_PATHS=").append(quote(String.join(" ", RUNTIME_PATHS))).append("\n");
        sb.append("backup_stack() {\n");
        sb.append("    rm -rf \"$BACKUP\" && mkdir -p \"$BACKUP\" || return 1\n");
        sb.append("    for p in $RUNTIME_PATHS; do\n");
        sb.append("        [ -e \"$p\" ] || continue\n");
        sb.append("        mkdir -p \"$BACKUP/$(dirname \"$p\")\" && cp -a \"$p\" \"$BACKUP/$p\" || return 1\n");
        sb.append("    done\n");
        sb.append("}\n");
        // Whole trees, not an overlay: a file the release added must not outlive the rollback.
        sb.append("restore_stack() {\n");
        sb.append("    for p in $RUNTIME_PATHS; do\n");
        sb.append("        rm -rf \"$p\"\n");
        sb.append("        if [ -e \"$BACKUP/$p\" ]; then mkdir -p \"$(dirname \"$p\")\"; "
            + "cp -a \"$BACKUP/$p\" \"$p\"; fi\n");
        sb.append("    done\n");
        sb.append("}\n");
        sb.append("put_back() {\n");
        sb.append("    [ -n \"$PREVIOUS_IMAGE\" ] && docker tag \"$PREVIOUS_IMAGE\" ")
            .append(quote(SelfUpdate.IMAGE_REPOSITORY + ":latest")).append(" >/dev/null 2>&1\n");
        sb.append("    restore_stack\n");
        sb.append("}\n");
        sb.append("backup_stack || { say FAILED 'stack-backup-failed'; exit 7; }\n\n");

        sb.append("docker compose pull ").append(svc)
            .append(" >> \"$LOG\" 2>&1 || { say FAILED 'pull-failed'; exit 3; }\n\n");

        // The commit the pulled image was built from. The runtime files come from that same commit, or the
        // compose file and the image disagree. A local build names none, and its files stay as they are.
        sb.append("REVISION=\"$(docker image inspect --format '{{index .Config.Labels \"").append(REVISION_LABEL)
            .append("\"}}' \"$(docker inspect --format '{{.Config.Image}}' \"$CID\" 2>/dev/null)\" 2>/dev/null)\"\n");
        sb.append("case \"$REVISION\" in ''|*[!0-9a-f]*) REVISION='' ;; esac\n\n");

        // That commit's own install.sh does the sync — its own RUNTIME_PATHS, its own tarball, and any
        // generated secret the release added — so the update and a hand re-run are the same code.
        sb.append("STACK='stack-not-synced'\n");
        sb.append("if [ -n \"$REVISION\" ]; then\n");
        sb.append("    if curl -fsSL \"https://raw.githubusercontent.com/").append(SOURCE_REPOSITORY)
            .append("/$REVISION/install.sh\" -o \"$INSTALLER\" "
                + "&& VAIER_REF=\"$REVISION\" bash \"$INSTALLER\" >> \"$LOG\" 2>&1; then\n");
        sb.append("        STACK=\"stack@$REVISION\"\n");
        sb.append("    else\n");
        sb.append("        put_back\n");
        sb.append("        say FAILED 'stack-sync-failed'\n");
        sb.append("        exit 8\n");
        sb.append("    fi\n");
        // tar run as root keeps the archive's root ownership; hand the files back to the install's owner.
        sb.append("    [ \"$(id -u)\" = 0 ] && chown -R --reference=. $RUNTIME_PATHS 2>/dev/null\n");
        sb.append("fi\n\n");

        // The whole project, not only Vaier: a changed init container re-runs, and an unchanged service is a
        // no-op. No --remove-orphans — a container compose no longer lists may be the operator's own.
        // The masquerade sidecar joins wireguard's netns at create time, so a recreated wireguard leaves it
        // dead unless it is recreated too; it always is, and a compose without it just refuses, harmlessly.
        sb.append("bring_up() {\n");
        sb.append("    docker compose up -d >> \"$LOG\" 2>&1 || return 1\n");
        sb.append("    docker compose up -d --force-recreate --no-deps ").append(quote(MASQUERADE_SERVICE))
            .append(" >> \"$LOG\" 2>&1\n");
        sb.append("    return 0\n");
        sb.append("}\n\n");

        // The container is on a bridge network with no published port, so it is reached at its own address —
        // the same way anything else on this host reaches it.
        sb.append("healthy() {\n");
        sb.append("    local cid ip\n");
        sb.append("    cid=\"$(docker compose ps -q ").append(svc).append(" 2>/dev/null)\"\n");
        sb.append("    [ -n \"$cid\" ] || return 1\n");
        sb.append("    ip=\"$(docker inspect -f "
            + "'{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \"$cid\" 2>/dev/null)\"\n");
        sb.append("    [ -n \"$ip\" ] || return 1\n");
        sb.append("    curl -fsS -m 5 \"http://$ip:8080/settings/version\" >/dev/null 2>&1\n");
        sb.append("}\n\n");

        sb.append("WHY='up-failed'\n");
        sb.append("if bring_up; then\n");
        sb.append("    WHY='no-answer'\n");
        sb.append("    DEADLINE=$((SECONDS + ").append(Math.max(1, healthTimeoutSeconds)).append("))\n");
        sb.append("    while [ $SECONDS -lt $DEADLINE ]; do\n");
        sb.append("        if healthy; then say UPGRADED \"$PREVIOUS_IMAGE $STACK\"; exit 0; fi\n");
        sb.append("        sleep 3\n");
        sb.append("    done\n");
        sb.append("fi\n\n");

        // It did not come up, or did not answer. Put back exactly what was running: the image by digest, and
        // the files it was started from. Compose recreates whatever that changes, Vaier included.
        sb.append("if [ -n \"$PREVIOUS_IMAGE\" ]; then\n");
        sb.append("    put_back\n");
        sb.append("    bring_up\n");
        sb.append("    say ROLLED_BACK \"$PREVIOUS_IMAGE $WHY\"\n");
        sb.append("    exit 5\n");
        sb.append("fi\n");
        sb.append("say FAILED 'no-previous-image-to-restore'\n");
        sb.append("exit 6\n");
        return sb.toString();
    }

    /**
     * The command that starts the script without tying it to the SSH session — or to the container — that
     * launched it. {@code setsid} detaches it from the session and {@code nohup} from the hangup, so killing
     * the Vaier container mid-update (which is the whole point of the exercise) cannot kill the update
     * halfway through, which is the worst possible moment for it to stop.
     */
    public static String launch(String composeDir, String runId) {
        return "setsid nohup bash " + quote(scriptPathFor(composeDir, runId))
            + " >/dev/null 2>&1 < /dev/null &";
    }

    /** Where the rendered script is staged on the host, per run so two never collide. */
    public static String scriptPathFor(String composeDir, String runId) {
        return composeDir + "/.vaier-update-" + runId + ".sh";
    }

    /** Where a compose-started container's project lives, and what compose calls it there. */
    public record ComposeLocation(String workingDir, String service) {}

    /**
     * Ask the host where Vaier's compose project is, rather than asking the operator. Docker stamps the
     * project's working directory and the service's name onto every container compose starts, so both facts
     * are already on the running container — and an env var the operator has to fill in is an env var they
     * can fill in wrongly, on the one operation where being wrong takes Vaier down and leaves it down.
     */
    public static String inspectComposeLabels(String containerId) {
        return "docker inspect --format "
            + "'{{index .Config.Labels \"com.docker.compose.project.working_dir\"}}"
            + "\t{{index .Config.Labels \"com.docker.compose.service\"}}' "
            + quote(containerId);
    }

    /**
     * Read that answer. Empty when either label is missing, which means this container was <b>not</b> started
     * by compose — there is no {@code docker compose up} that would bring it back, so an update would take
     * Vaier down and leave it down. Refusing is the only safe reading. Never throws.
     */
    public static java.util.Optional<ComposeLocation> parseComposeLabels(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return java.util.Optional.empty();
        }
        String[] parts = stdout.strip().split("\t", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ComposeLocation(parts[0].strip(), parts[1].strip()));
    }

    /** Write the rendered script onto the host, the same base64 way the borg setup scripts are staged. */
    public static String stage(String script, String path) {
        return "mkdir -p \"$(dirname " + quote(path) + ")\"; "
            + "printf %s " + quote(java.util.Base64.getEncoder()
                .encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            + " | base64 -d > " + quote(path) + "; "
            + "chmod +x " + quote(path) + "; echo STAGED";
    }

    /** Read the account the last update left, if any. Absence is not an error — most hosts have none. */
    public static String readResult() {
        return "cat \"" + RESULT_FILE + "\" 2>/dev/null || true";
    }

    /** Single-quoted for the shell, with any embedded quote closed and re-opened — as {@code BorgCommand} does. */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
