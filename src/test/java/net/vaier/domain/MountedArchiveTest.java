package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The domain rule behind browsing the past: an {@link Archive} mounted as a read-only filesystem on its
 * machine, and the total mapping between a file's coordinate <em>inside the archive</em> and its real
 * coordinate under the mountpoint. borg strips the leading {@code /} when it mounts, so the mapping is a
 * trivial prefix — and it reuses {@link FileEntry#normalisePath}, so a path that would climb above the
 * archive root is refused in the past exactly as it is in the present.
 */
class MountedArchiveTest {

    private static final Instant WHEN = Instant.parse("2026-07-14T02:00:00Z");

    @Test
    void under_keysTheMountpointByArchiveId_notItsName() {
        // Archive names carry ':' (colina-2026-07-14T02:00:00) and cannot be a directory; the id is opaque hex.
        MountedArchive mounted = MountedArchive.under("/home/ubuntu/.vaier-backup", "9f8e7d6c");

        assertThat(mounted.mountpoint()).isEqualTo("/home/ubuntu/.vaier-backup/mounts/9f8e7d6c");
    }

    @Test
    void under_normalisesTheWorkDir() {
        assertThat(MountedArchive.under("/home/ubuntu//.vaier-backup/", "ab12").mountpoint())
            .isEqualTo("/home/ubuntu/.vaier-backup/mounts/ab12");
    }

    @Test
    void under_refusesAnArchiveIdThatIsNotOpaqueHex() {
        // The id becomes a path segment, so anything that could break out of it — a slash, a '..', a shell
        // metacharacter — is refused before it can ever reach a mountpoint.
        assertThatThrownBy(() -> MountedArchive.under("/home/ubuntu/.vaier-backup", "../etc"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MountedArchive.under("/home/ubuntu/.vaier-backup", "a/b"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MountedArchive.under("/home/ubuntu/.vaier-backup", ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MountedArchive.under("/home/ubuntu/.vaier-backup", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void under_refusesAWorkDirThatIsNotAbsolute() {
        assertThatThrownBy(() -> MountedArchive.under("relative/dir", "ab12"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void machinePath_prependsTheMountpoint_toTheArchivePath() {
        MountedArchive mounted = MountedArchive.under("/home/ubuntu/.vaier-backup", "ab12");

        // A file at /home/geir/notes.txt in the archive is really at <mountpoint>/home/geir/notes.txt.
        assertThat(mounted.machinePath("/home/geir/notes.txt"))
            .isEqualTo("/home/ubuntu/.vaier-backup/mounts/ab12/home/geir/notes.txt");
    }

    @Test
    void machinePath_ofTheArchiveRoot_isTheMountpointItself() {
        MountedArchive mounted = MountedArchive.under("/home/ubuntu/.vaier-backup", "ab12");

        assertThat(mounted.machinePath("/")).isEqualTo("/home/ubuntu/.vaier-backup/mounts/ab12");
    }

    @Test
    void machinePath_refusesAPathThatClimbsAboveTheArchiveRoot() {
        MountedArchive mounted = MountedArchive.under("/home/ubuntu/.vaier-backup", "ab12");

        // The whole point of reusing normalisePath: the past is jailed to the archive exactly as the present
        // is jailed to the root. /../../etc must not escape the mount into the live machine.
        assertThatThrownBy(() -> mounted.machinePath("/../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toArchivePath_stripsTheMountpoint_backToTheArchiveCoordinate() {
        MountedArchive mounted = MountedArchive.under("/home/ubuntu/.vaier-backup", "ab12");

        assertThat(mounted.toArchivePath("/home/ubuntu/.vaier-backup/mounts/ab12/home/geir"))
            .isEqualTo("/home/geir");
        assertThat(mounted.toArchivePath("/home/ubuntu/.vaier-backup/mounts/ab12"))
            .isEqualTo("/");
    }

    @Test
    void toArchivePath_refusesAPathThatIsNotUnderTheMount() {
        MountedArchive mounted = MountedArchive.under("/home/ubuntu/.vaier-backup", "ab12");

        assertThatThrownBy(() -> mounted.toArchivePath("/etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anchor_movesEntriesFromMachineCoordinatesBackOntoArchiveCoordinates() {
        MountedArchive mounted = MountedArchive.under("/home/ubuntu/.vaier-backup", "ab12");
        String mp = "/home/ubuntu/.vaier-backup/mounts/ab12";

        List<FileEntry> anchored = mounted.anchor(List.of(
            new FileEntry("geir", mp + "/home/geir", true, 4096, WHEN),
            new FileEntry("hosts", mp + "/etc/hosts", false, 200, WHEN)));

        // The browser must see the file's own path in the archive — the same coordinate the live tree uses —
        // never the throwaway mountpoint the archive happens to be mounted under.
        assertThat(anchored).extracting(FileEntry::path)
            .containsExactly("/home/geir", "/etc/hosts");
    }
}
