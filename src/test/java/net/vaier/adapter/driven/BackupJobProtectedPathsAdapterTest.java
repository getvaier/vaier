package net.vaier.adapter.driven;

import net.vaier.domain.BackupJob;
import net.vaier.domain.ProtectedPaths;
import net.vaier.domain.port.ForPersistingBackupJobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BackupJobProtectedPathsAdapterTest {

    static final class InMemoryJobs implements ForPersistingBackupJobs {
        final List<BackupJob> store = new ArrayList<>();
        @Override public List<BackupJob> getAll() { return List.copyOf(store); }
        @Override public Optional<BackupJob> getByName(String name) {
            return store.stream().filter(j -> j.name().equals(name)).findFirst();
        }
        @Override public List<BackupJob> getByMachine(String machineName) {
            return store.stream().filter(j -> j.machineName().equals(machineName)).toList();
        }
        @Override public void save(BackupJob j) { store.removeIf(x -> x.name().equals(j.name())); store.add(j); }
        @Override public void deleteByName(String name) { store.removeIf(j -> j.name().equals(name)); }
    }

    InMemoryJobs jobs;
    BackupJobProtectedPathsAdapter adapter;

    @BeforeEach
    void setUp() {
        jobs = new InMemoryJobs();
        adapter = new BackupJobProtectedPathsAdapter(jobs);
    }

    private BackupJob job(String name, List<String> sources, List<String> excludes) {
        return new BackupJob(name, "Apalveien 5", "apalveien-5", sources, excludes,
            7, 4, 6, "zstd,6", true, false);
    }

    @Test
    void aMachineWithNoJobProtectsNothing() {
        assertThat(adapter.protectedPathsFor("Apalveien 5").isEmpty()).isTrue();
    }

    @Test
    void theJobsSourcePathsAreProtected() {
        jobs.save(job("apalveien-5", List.of("/home"), List.of()));

        assertThat(adapter.protectedPathsFor("Apalveien 5").covers("/home/openhab")).isTrue();
    }

    @Test
    void anExcludedFolderIsNotProtected_soTheExplorerCannotKeepShowingItAsBackedUp() {
        // The second half of the bug: reading only the source paths meant an excluded folder still wore a full
        // shield, and the fix to "stop backing up" would have looked like it did nothing at all.
        jobs.save(job("apalveien-5", List.of("/home"), List.of("/home/openhab/userdata/logs")));

        ProtectedPaths paths = adapter.protectedPathsFor("Apalveien 5");

        assertThat(paths.covers("/home/openhab/userdata/logs")).isFalse();
        assertThat(paths.covers("/home/openhab/userdata/logs/openhab.log")).isFalse();
        assertThat(paths.covers("/home/openhab/userdata")).isTrue();
    }

    @Test
    void severalJobsOnOneMachineAreReadAsOneProtection() {
        jobs.save(job("apalveien-5", List.of("/home"), List.of("/home/openhab")));
        jobs.save(job("apalveien-5-etc", List.of("/etc"), List.of()));

        ProtectedPaths paths = adapter.protectedPathsFor("Apalveien 5");

        assertThat(paths.covers("/etc/nginx")).isTrue();
        assertThat(paths.covers("/home/geir")).isTrue();
        assertThat(paths.covers("/home/openhab")).isFalse();
    }
}
