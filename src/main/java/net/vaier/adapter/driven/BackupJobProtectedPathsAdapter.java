package net.vaier.adapter.driven;

import net.vaier.domain.BackupJob;
import net.vaier.domain.SourcePaths;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForReadingProtectedPaths;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads a machine's protected paths straight from the fleet-backup job store. Translation only: it gathers
 * the source paths of every job that backs up the machine and hands them to the domain
 * {@link SourcePaths#of} to normalize — it makes no coverage decision of its own. A machine with no job
 * yields an empty {@link SourcePaths}, so the Explorer marks nothing on it.
 */
@Component
public class BackupJobProtectedPathsAdapter implements ForReadingProtectedPaths {

    private final ForPersistingBackupJobs jobs;

    public BackupJobProtectedPathsAdapter(ForPersistingBackupJobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public SourcePaths protectedPathsFor(String machineName) {
        List<String> allPaths = jobs.getByMachine(machineName).stream()
            .map(BackupJob::sourcePaths)
            .flatMap(List::stream)
            .toList();
        return SourcePaths.of(allPaths);
    }
}
