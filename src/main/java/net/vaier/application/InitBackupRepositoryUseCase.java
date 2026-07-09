package net.vaier.application;

/**
 * Provision a fleet-backup repository from a client host: install the passphrase file and run
 * {@code borg init} to create the repository on the NAS. This is the seam the
 * {@code BackupRestController} depends on for the provisioning wizard — every controller reaches its
 * behaviour through a {@code *UseCase}, never a rest-layer component directly.
 *
 * <p>The implementation ({@code rest/BackupProvisioner}) runs over SSH via {@link RunRemoteCommandUseCase}
 * with the same machine/SSH/credential guards as the runner. It never throws: a guard failure or a real
 * borg error comes back as a {@link RepoInitResult} with {@code initialized=false} and a message, and a
 * repository that already exists is treated as a successful, idempotent init.
 */
public interface InitBackupRepositoryUseCase {

    /** Init the repository named {@code repositoryName} from the host {@code machineName}. */
    RepoInitResult initRepo(String repositoryName, String machineName);

    /**
     * The outcome of an init attempt: {@code initialized} true when the repository is now ready (a fresh
     * init or an already-existing one), {@code alreadyExisted} true only when borg reported it already
     * existed, and {@code message} a short human-readable summary.
     */
    record RepoInitResult(boolean initialized, boolean alreadyExisted, String message) {}
}
