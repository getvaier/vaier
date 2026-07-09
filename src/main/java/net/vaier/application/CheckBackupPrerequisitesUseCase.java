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
     * The borg found on a host: {@code installed} is false when borg is absent or the probe could not be
     * read, otherwise {@code version} carries the parsed {@link BorgVersion} and {@code supported} reflects
     * {@link BorgVersion#isSupported()}.
     */
    record BorgAvailability(boolean installed, Optional<BorgVersion> version, boolean supported) {}

    /** Whether the NAS borg port is reachable from the checked host. */
    record RepoReachability(boolean reachable) {}
}
