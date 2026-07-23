package net.vaier.domain;

/**
 * The script Vaier runs on its own host to replace itself, and the command that launches it.
 *
 * <p>Everything about its shape follows from one fact: <b>the process that asks for the upgrade dies in the
 * middle of it</b>. A container cannot recreate itself — the instant {@code docker compose up -d} replaces
 * it, whatever was driving the upgrade is gone, along with any in-memory record that it was happening and any
 * connection it might have reported to. So the work is handed to the host and detached, and it has to be able
 * to finish, judge itself, undo itself and leave an account, with nobody listening.
 *
 * <p><b>The rollback is the feature.</b> If a bad image comes up, the thing that is down is the thing an
 * operator would use to fix it — Vaier is the fleet's control plane, its web terminal and its file browser.
 * So the script pins what was running <em>before</em> it touches anything, by digest and not by tag (the tag
 * moves under it during the pull, so rolling "back" to {@code :latest} would roll forward to the broken image
 * again), and puts that back if the new one does not answer.
 *
 * <p>Rendering the script here rather than in a service follows {@link BorgServerSetupScript} and
 * {@link BorgClientSetupScript}: what the host is told to do is a domain rule, and the runner only carries it.
 */
public final class SelfUpgradeScript {

    /**
     * Where the script leaves its account of what happened. Vaier is restarting while it runs, so there is no
     * in-memory state to settle against — the outcome has to outlive both the old process and the script, and
     * be waiting on disk when the new one boots.
     */
    public static final String RESULT_FILE = "/var/lib/vaier-upgrade/last-upgrade";

    /** How long to wait for the replacement to answer before deciding it will not. */
    public static final int DEFAULT_HEALTH_TIMEOUT_SECONDS = 120;

    private SelfUpgradeScript() {}

    /**
     * Render the upgrade script. It pins the running image, pulls, recreates, waits (bounded) for the new
     * container to answer on the endpoint that reports Vaier's version — so "it came up" and "it is the build
     * we asked for" are one check — and on silence puts the pinned image back. Every path out writes a single
     * line to {@link #RESULT_FILE}: {@code UPGRADED}, {@code ROLLED_BACK} or {@code FAILED}, with the run id
     * so a stale result from an earlier upgrade is never mistaken for this one's.
     */
    public static String generate(String composeDir, String service, String runId, int healthTimeoutSeconds) {
        String dir = quote(composeDir);
        String svc = quote(service);
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("#\n");
        sb.append("# Vaier self-upgrade. Runs detached on the host, because it replaces the container that\n");
        sb.append("# asked for it. Rolls back to the previously running image if the new one does not answer.\n");
        sb.append("#\n");
        // Deliberately not `set -e`: a failing step must reach the result file, not abort the script and
        // leave the host with no account of what happened.
        sb.append("set -uo pipefail\n\n");

        sb.append("RUN_ID=").append(quote(runId)).append("\n");
        sb.append("RESULT=").append(quote(RESULT_FILE)).append("\n");
        sb.append("mkdir -p \"$(dirname \"$RESULT\")\"\n\n");

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

        sb.append("docker compose pull ").append(svc)
            .append(" || { say FAILED 'pull-failed'; exit 3; }\n");
        sb.append("docker compose up -d --force-recreate ").append(svc)
            .append(" || { say FAILED 'recreate-failed'; exit 4; }\n\n");

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

        sb.append("DEADLINE=$((SECONDS + ").append(Math.max(1, healthTimeoutSeconds)).append("))\n");
        sb.append("while [ $SECONDS -lt $DEADLINE ]; do\n");
        sb.append("    if healthy; then say UPGRADED \"$PREVIOUS_IMAGE\"; exit 0; fi\n");
        sb.append("    sleep 3\n");
        sb.append("done\n\n");

        // It did not answer. Put back exactly what was running, by digest.
        sb.append("if [ -n \"$PREVIOUS_IMAGE\" ]; then\n");
        sb.append("    docker tag \"$PREVIOUS_IMAGE\" ")
            .append(quote(SelfUpgrade.IMAGE_REPOSITORY + ":latest")).append(" >/dev/null 2>&1\n");
        sb.append("    docker compose up -d --force-recreate ").append(svc).append(" >/dev/null 2>&1\n");
        sb.append("    say ROLLED_BACK \"$PREVIOUS_IMAGE\"\n");
        sb.append("    exit 5\n");
        sb.append("fi\n");
        sb.append("say FAILED 'no-previous-image-to-restore'\n");
        sb.append("exit 6\n");
        return sb.toString();
    }

    /**
     * The command that starts the script without tying it to the SSH session — or to the container — that
     * launched it. {@code setsid} detaches it from the session and {@code nohup} from the hangup, so killing
     * the Vaier container mid-upgrade (which is the whole point of the exercise) cannot kill the upgrade
     * halfway through, which is the worst possible moment for it to stop.
     */
    public static String launch(String composeDir, String runId) {
        return "setsid nohup bash " + quote(scriptPathFor(composeDir, runId))
            + " >/dev/null 2>&1 < /dev/null &";
    }

    /** Where the rendered script is staged on the host, per run so two never collide. */
    public static String scriptPathFor(String composeDir, String runId) {
        return composeDir + "/.vaier-upgrade-" + runId + ".sh";
    }

    /** Single-quoted for the shell, with any embedded quote closed and re-opened — as {@code BorgCommand} does. */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
