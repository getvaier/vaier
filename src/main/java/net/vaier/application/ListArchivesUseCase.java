package net.vaier.application;

import net.vaier.domain.Archive;

import java.util.List;

/**
 * List the {@link Archive}s held in a fleet-backup repository. This is the seam the
 * {@code BackupRestController} depends on to browse a repository's archives — every controller reaches
 * its behaviour through a {@code *UseCase}, never a rest-layer component directly.
 *
 * <p>Listing needs a client host to run {@code borg list} from (a repository alone has no machine), so
 * the implementation ({@code rest/BackupRunner}) resolves the repository, picks a machine from a job
 * that targets it, and runs the list over SSH. It never throws: an unknown repository, no referencing
 * job, an unreachable host, or a failed {@code borg list} all yield an empty list.
 */
public interface ListArchivesUseCase {

    /** Every archive in the repository named {@code repositoryName}, or empty when it cannot be listed. */
    List<Archive> listArchives(String repositoryName);
}
