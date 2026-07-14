package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Explorer's file entry and the rules that govern it: what counts as a browsable path, how a
 * child's path is built from its directory, and the order a directory's entries are listed in.
 */
class FileEntryTest {

    private static final Instant WHEN = Instant.parse("2026-07-13T10:15:30Z");

    private static FileEntry dir(String parent, String name) {
        return FileEntry.in(parent, name, true, 4096, WHEN);
    }

    private static FileEntry file(String parent, String name) {
        return FileEntry.in(parent, name, false, 120, WHEN);
    }

    // --- what a path is ------------------------------------------------------------------------

    @Test
    void anAbsoluteNormalisedPath_isKeptAsIs() {
        assertThat(FileEntry.normalisePath("/home/geir")).isEqualTo("/home/geir");
    }

    @Test
    void theRoot_isAPath() {
        assertThat(FileEntry.normalisePath("/")).isEqualTo("/");
    }

    @Test
    void redundantSlashesDotsAndTrailingSlash_areNormalisedAway() {
        assertThat(FileEntry.normalisePath("/home//geir/./docs/")).isEqualTo("/home/geir/docs");
    }

    @Test
    void climbingToTheParent_resolvesToTheParent() {
        // The "up" navigation of any file browser — legitimate, and it must land on the real parent.
        assertThat(FileEntry.normalisePath("/home/geir/..")).isEqualTo("/home");
    }

    @Test
    void aRelativePath_isRefused() {
        assertThatThrownBy(() -> FileEntry.normalisePath("home/geir"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute");
    }

    @Test
    void aBlankOrMissingPath_isRefused() {
        assertThatThrownBy(() -> FileEntry.normalisePath(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileEntry.normalisePath("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    // --- hostile paths (the path comes from the browser) ----------------------------------------

    @Test
    void traversalAboveTheRoot_isRefused_notSilentlyClamped() {
        // "/../etc/passwd" and friends must not quietly become "/etc/passwd" — a path that climbs off the
        // top of the tree is not a path at all, and answering it would teach a caller that it worked.
        assertThatThrownBy(() -> FileEntry.normalisePath("/../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("above the root");
        assertThatThrownBy(() -> FileEntry.normalisePath("/home/geir/../../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("above the root");
    }

    @Test
    void aNulByteInThePath_isRefused() {
        // A NUL truncates the path in any C-side consumer — "/tmp/safe\0/../../etc" is a classic.
        assertThatThrownBy(() -> FileEntry.normalisePath("/tmp/safe\0/../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NUL");
    }

    @Test
    void shellMetacharacters_arePreservedVerbatim_becauseNoShellIsEverInvolved() {
        // Explorer speaks SFTP, a binary protocol — there is no command line to inject into. These are
        // legal Linux filenames and must survive intact, or a real file becomes unreachable.
        assertThat(FileEntry.normalisePath("/tmp/$(rm -rf ~)")).isEqualTo("/tmp/$(rm -rf ~)");
        assertThat(FileEntry.normalisePath("/tmp/a;b`c`|d")).isEqualTo("/tmp/a;b`c`|d");
    }

    // --- building an entry's path from its directory --------------------------------------------

    @Test
    void anEntrysPath_isItsNameJoinedToItsDirectory() {
        assertThat(file("/home/geir", "notes.txt").path()).isEqualTo("/home/geir/notes.txt");
    }

    @Test
    void anEntryDirectlyUnderTheRoot_getsASingleSlash() {
        assertThat(dir("/", "etc").path()).isEqualTo("/etc");
    }

    @Test
    void anEntryUnderAnUnnormalisedDirectory_isStillNormalised() {
        assertThat(file("/home//geir/", "notes.txt").path()).isEqualTo("/home/geir/notes.txt");
    }

    @Test
    void aNameThatIsAPathSegmentItself_isRefused() {
        // A server that answers readdir with "../../etc" must not be able to fabricate an entry.
        assertThatThrownBy(() -> file("/home/geir", "../etc"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> file("/home/geir", ".."))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> file("/home/geir", ""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNegativeSize_isRefused() {
        assertThatThrownBy(() -> FileEntry.in("/home/geir", "notes.txt", false, -1, WHEN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- listing order --------------------------------------------------------------------------

    @Test
    void aListing_putsDirectoriesBeforeFiles_thenSortsByNameIgnoringCase() {
        List<FileEntry> ordered = FileEntry.listing(List.of(
            file("/srv", "zeta.txt"),
            dir("/srv", "media"),
            file("/srv", "Alpha.txt"),
            dir("/srv", "Backups"),
            file("/srv", "beta.txt")));

        assertThat(ordered).extracting(FileEntry::name)
            .containsExactly("Backups", "media", "Alpha.txt", "beta.txt", "zeta.txt");
    }

    @Test
    void aListing_ofNothing_isEmpty_notNull() {
        assertThat(FileEntry.listing(List.of())).isEmpty();
    }
}
