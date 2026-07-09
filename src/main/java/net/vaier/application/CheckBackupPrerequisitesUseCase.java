package net.vaier.application;

import net.vaier.domain.BorgVersion;

import java.util.Optional;

/**
 * Guided-provisioning read checks for a fleet-backup host: is borg installed (and new enough), and can
 * the host reach the NAS borg port over the tunnel. This is the seam the {@code BackupRestController}
 * depends on to power the provisioning wizard — every controller reaches its behaviour through a
 * {@code *UseCase}, never a rest-layer component directly.
 *
 * <p>The implementation ({@code rest/BackupProvisioner}) runs the probes over SSH via
 * {@link RunRemoteCommandUseCase}, applying the same machine/SSH/credential guards as the runner. Neither
 * check throws: an unreachable host or a guard that is not met simply reports "not installed" / "not
 * reachable".
 */
public interface CheckBackupPrerequisitesUseCase {

    /** Whether borg is installed on {@code machineName}, and if so its version and whether it is supported. */
    BorgAvailability checkBorg(String machineName);

    /** Whether {@code machineName} can reach the NAS borg port of the repository named {@code repositoryName}. */
    RepoReachability checkNas(String repositoryName, String machineName);

    /**
     * The decisive check the wizard was missing: from {@code machineName}, actually authenticate to the
     * repository's Backup server (SSH + {@code borg --version}) rather than merely seeing the port open. It
     * reports whether the client's key is trusted ({@code borgAuthOk}), the server's borg version, and —
     * given the client's own borg version ({@code clientBorgVersion}, from {@link #checkBorg}) — whether the
     * two are protocol-compatible. This is what kills the false all-green: a host with borg installed and the
     * NAS port open can still fail {@code Permission denied (publickey)}, and a borg-1.x client cannot talk to
     * a borg-2.x server. Never throws: a guarded-out host or a failed probe comes back as a negative result.
     */
    ServerBorgAuth checkServerAuth(String repositoryName, String machineName,
                                   Optional<BorgVersion> clientBorgVersion);

    /**
     * The borg found on a host: {@code installed} is false when borg is absent or the probe could not be
     * read, otherwise {@code version} carries the parsed {@link BorgVersion} and {@code supported} reflects
     * {@link BorgVersion#isSupported()}.
     */
    record BorgAvailability(boolean installed, Optional<BorgVersion> version, boolean supported) {}

    /** Whether the NAS borg port is reachable from the checked host. */
    record RepoReachability(boolean reachable) {}

    /**
     * The outcome of authenticating to the Backup server from the client: {@code authOk} is whether the
     * client's key is trusted (borg answered over SSH), {@code serverVersion} the server's borg version when
     * it could be read, and {@code versionsCompatible} whether the client and server borg majors match.
     * {@code versionsCompatible} is honest by construction — it is {@code false} whenever either version is
     * unknown, never optimistically true (see {@link BorgVersion#compatible}).
     */
    record ServerBorgAuth(boolean authOk, Optional<BorgVersion> serverVersion, boolean versionsCompatible) {}
}
