package net.vaier.domain;

import net.vaier.domain.port.ForProbingTcp.ProbeResult;

/**
 * A host running a borg server ({@code borg serve}) that Vaier can address over SSH, and on which one or
 * more {@link BackupRepository} live. This is the "Backup server" of the fleet-backup feature: it carries
 * the <em>server coordinates</em> (how to reach the {@code borg serve} sshd) that used to be fused onto
 * each {@link BackupRepository}, so a repository becomes simply "a name on a server".
 *
 * <p>The entity owns the borg/ssh knowledge that is purely about the server's location:
 * {@link #sshUrlPrefix()} renders the {@code ssh://user@host:port} prefix a repository's borg URL is built
 * on, and {@link #authorizedKeysPath()} names the host-side {@code authorized_keys} the borg client key
 * must be trusted in. No secrets live here — a repository's passphrase stays on {@link BackupRepository} —
 * so this entity is stored in the clear.
 *
 * @param name           the Vaier-facing identity of this server, unique within the store
 * @param machineName    the Vaier {@link Machine} hosting it (e.g. {@code "NAS"}); how Vaier SSHes to it
 * @param host           the host the borg-server sshd is reachable at
 * @param sshPort        the port the borg container's sshd listens on (default {@value #DEFAULT_SSH_PORT})
 * @param borgUser       the SSH user the borg client key authenticates as (default {@value #DEFAULT_BORG_USER})
 * @param baseRepoPath   the base path new repositories derive their path under; <strong>no leading
 *                       slash</strong> — the borg URL inserts the {@code /} (default {@value #DEFAULT_BASE_REPO_PATH})
 * @param serverDataPath the host-side data directory of the borg container (e.g. {@code /volume1/docker/borg});
 *                       host-specific and may be null/blank until provisioning discovers or sets it
 * @param managed        {@code true} when Vaier provisioned this server, {@code false} when it was adopted
 */
public record BackupServer(
    String name,
    String machineName,
    String host,
    int sshPort,
    String borgUser,
    String baseRepoPath,
    String serverDataPath,
    boolean managed
) {

    /** The borg-server container's sshd port (not the host's own SSH). */
    public static final int DEFAULT_SSH_PORT = 8022;

    /** The conventional SSH user the borg client key authenticates as. */
    public static final String DEFAULT_BORG_USER = "borg";

    /** The base repository path new repositories derive under; no leading slash (the URL inserts it). */
    public static final String DEFAULT_BASE_REPO_PATH = "home/borg/backups";

    public BackupServer {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Backup server name must not be blank");
        }
        if (machineName == null || machineName.isBlank()) {
            throw new IllegalArgumentException("Backup server machineName must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Backup server host must not be blank");
        }
        if (sshPort < 1 || sshPort > 65535) {
            throw new IllegalArgumentException("Backup server sshPort must be 1..65535, was " + sshPort);
        }
        // Default the conventional fields in the compact constructor, mirroring BackupJob's compression.
        borgUser = (borgUser == null || borgUser.isBlank()) ? DEFAULT_BORG_USER : borgUser;
        baseRepoPath = (baseRepoPath == null || baseRepoPath.isBlank()) ? DEFAULT_BASE_REPO_PATH : baseRepoPath;
    }

    /** The {@code ssh://<borgUser>@<host>:<sshPort>} prefix a repository's borg URL is built on. */
    public String sshUrlPrefix() {
        return "ssh://" + borgUser + "@" + host + ":" + sshPort;
    }

    /**
     * A deterministic, filesystem- and shell-safe id for provisioning this server, used to name the
     * on-host {@code <runId>.sh}/{@code .rc}/{@code .log} files the detached setup run writes. Unlike a
     * {@link BackupRun} (many per job, disambiguated by a timestamp), provisioning is idempotent and
     * one-per-server, so the id carries <em>no</em> timestamp — it can be re-derived from the server name
     * alone, which is exactly what lets the status endpoint poll a running provision with no stored state.
     * Any character outside {@code [A-Za-z0-9._-]} in the name is collapsed to {@code -}, mirroring
     * {@link BackupJob#runId}. Deriving a safe id is the server's own decision, so it lives here.
     */
    public String provisionRunId() {
        return "provision-" + name.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    /**
     * The host-side {@code authorized_keys} the borg client key must be trusted in for key-based auth to
     * work. Derived from {@link #serverDataPath()} ({@code <serverDataPath>/ssh/authorized_keys}).
     *
     * <p>Throws {@link IllegalStateException} when {@code serverDataPath} is blank: key trust is a hard
     * prerequisite that cannot be located without knowing the borg container's host data directory, so a
     * blank path is a programming/state error to surface loudly rather than a value to represent as absent.
     */
    public String authorizedKeysPath() {
        if (serverDataPath == null || serverDataPath.isBlank()) {
            throw new IllegalStateException(
                "Backup server '" + name + "' has no serverDataPath; cannot locate authorized_keys");
        }
        return serverDataPath + "/ssh/authorized_keys";
    }

    /**
     * The host-side file the setup script publishes the borg container's <em>public</em> SSH host keys to,
     * for clients to pin (Slice 8). Derived from {@link #serverDataPath()}
     * ({@code <serverDataPath>/ssh/host_keys.pub}). Vaier reads it from the server's machine and installs the
     * keys into each authorized client's {@code known_hosts}, so borg never has to trust-on-first-use.
     *
     * <p>Throws {@link IllegalStateException} when {@code serverDataPath} is blank, for the same reason as
     * {@link #authorizedKeysPath()}: the file cannot be located without the container's host data directory.
     */
    public String hostKeysPath() {
        if (serverDataPath == null || serverDataPath.isBlank()) {
            throw new IllegalStateException(
                "Backup server '" + name + "' has no serverDataPath; cannot locate host_keys.pub");
        }
        return serverDataPath + "/ssh/host_keys.pub";
    }

    /** Subject for the admin alert sent once when this server crosses to down. */
    public String downSubject() {
        return "[Vaier] Backup server down: " + name + " on " + host;
    }

    /** Subject for the admin all-clear sent once when a previously down server answers again. */
    public String recoverySubject() {
        return "[Vaier] Backup server recovered: " + name + " on " + host;
    }

    /**
     * Body for the "backup server down" admin email. The {@code cause} changes what the operator must do, so
     * the message names it explicitly: {@link ProbeResult#REFUSED} means the host is alive but nothing is
     * listening — the <b>borg server container is down</b>; {@link ProbeResult#UNREACHABLE} means the
     * <b>host is unreachable</b> (host down / network / VPN tunnel). Rendering lives here on the entity,
     * mirroring {@link BackupRun#failureBody}; the notification service only sequences the SMTP send.
     *
     * <p>Note: this alert answers "is the backup server up?" as seen <em>from Vaier</em> — it is not a claim
     * that a backup client can route to it (that asymmetry is the Colina bug). The per-job {@code checkNas}
     * probe, which runs from the client host, remains authoritative for "can jobs reach it?".
     */
    public String downBody(String baseDomain, ProbeResult cause) {
        StringBuilder body = new StringBuilder();
        body.append("Backup server: ").append(name).append("\n");
        body.append("Machine: ").append(machineName).append("\n");
        body.append("Host: ").append(host).append(":").append(sshPort).append("\n");
        body.append("\n").append(describeCause(cause)).append("\n");
        appendVaierLink(body, baseDomain);
        return body.toString();
    }

    /** Body for the all-clear email; same shape as {@link #downBody}, cf. {@link BackupRun#recoveryBody}. */
    public String recoveryBody(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append("Backup server: ").append(name).append("\n");
        body.append("Machine: ").append(machineName).append("\n");
        body.append("Host: ").append(host).append(":").append(sshPort).append("\n");
        body.append("\nThe borg server on ").append(host).append(" is reachable again.\n");
        appendVaierLink(body, baseDomain);
        return body.toString();
    }

    /** The cause-specific sentence: a refused connect is a dead container, an unreachable host is a dead host. */
    private String describeCause(ProbeResult cause) {
        return switch (cause) {
            case REFUSED -> "The borg server container is down on " + host
                + " — the host is reachable but nothing is listening on port " + sshPort + ".";
            case UNREACHABLE -> host + " is unreachable"
                + " — the host may be down, or the network or VPN tunnel to it is broken.";
            // CONNECTED is not a down cause; render defensively rather than throw inside an alert path.
            case CONNECTED -> "The borg server on " + host + " is not healthy.";
        };
    }

    private void appendVaierLink(StringBuilder body, String baseDomain) {
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
    }
}
