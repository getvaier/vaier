package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectedPathsTest {

    private ProtectedPaths protecting(List<String> sources, List<String> excludes) {
        return ProtectedPaths.of(SourcePaths.of(sources), Excludes.of(excludes));
    }

    @Test
    void aPathUnderASourcePathIsBackedUp() {
        assertThat(protecting(List.of("/home"), List.of()).covers("/home/openhab")).isTrue();
    }

    @Test
    void anExcludedPathIsNotBackedUp_evenThoughAnAncestorIsProtected() {
        // The reproduction: /home is protected, its openhab logs folder was unprotected and so excluded. The
        // folder must lose its shield — the operator was told it stopped being backed up, and it did.
        ProtectedPaths paths = protecting(List.of("/home"), List.of("/home/openhab/userdata/logs"));

        assertThat(paths.covers("/home/openhab/userdata/logs")).isFalse();
        assertThat(paths.covers("/home/openhab/userdata/logs/openhab.log")).isFalse();
        assertThat(paths.covers("/home/openhab/userdata")).isTrue();
        assertThat(paths.covers("/home/openhab/userdata/logs2")).isTrue();
    }

    @Test
    void aFolderHoldingOnlyExcludedContentDoesNotEvenWearAHalfShield() {
        // /home/openhab/userdata/logs is the only thing under it, and it is excluded — nothing inside is kept.
        ProtectedPaths paths = protecting(List.of("/home/openhab/userdata/logs"),
            List.of("/home/openhab/userdata/logs"));

        assertThat(paths.covers("/home/openhab/userdata")).isFalse();
        assertThat(paths.enclosesUnder("/home/openhab/userdata")).isFalse();
    }

    @Test
    void aFolderStillHoldingOneUnexcludedSourcePathWearsAHalfShield() {
        ProtectedPaths paths = protecting(List.of("/home/a", "/home/b"), List.of("/home/a"));

        assertThat(paths.covers("/home")).isFalse();
        assertThat(paths.enclosesUnder("/home")).isTrue();
    }

    @Test
    void backedUpAndContainsBackedUpStayMutuallyExclusive() {
        ProtectedPaths paths = protecting(List.of("/home"), List.of("/home/openhab"));

        assertThat(paths.covers("/home")).isTrue();
        assertThat(paths.enclosesUnder("/home")).isFalse();
    }

    @Test
    void aBackedUpPathNeverAlsoContainsBackedUp_evenWhenASourcePathLivesInsideIt() {
        // The hard case for the mutual exclusion, and the reason it must be enforced HERE: a source set that is
        // not a minimal cover (built directly rather than through SourcePaths.of) holds both /home and a path
        // inside it, so the "some member is strictly under" half of the rule genuinely fires. The verdict must
        // still be a single shield — "backed up" and "contains backed up" are alternatives, never both — so no
        // caller ever needs to re-guard it.
        ProtectedPaths paths = ProtectedPaths.of(
            new SourcePaths(List.of("/home", "/home/geir")), Excludes.none());

        assertThat(paths.covers("/home")).isTrue();
        assertThat(paths.enclosesUnder("/home")).isFalse();
    }

    @Test
    void noneProtectsNothing() {
        assertThat(ProtectedPaths.none().isEmpty()).isTrue();
        assertThat(ProtectedPaths.none().covers("/home")).isFalse();
        assertThat(ProtectedPaths.none().enclosesUnder("/home")).isFalse();
        assertThat(ProtectedPaths.none().isBackedUp("/home")).isFalse();
        assertThat(ProtectedPaths.none().containsBackedUp("/home")).isFalse();
    }

    // --- the shield a folder wears, which is not the same question as "is this path covered" --------------
    //
    // Reported on Colina 27 and Apalveien 5: /home is protected, the openhab logs folder inside it is
    // excluded, and /home still wore a FULL shield. A full shield is a promise that everything under a folder
    // is in the archive. With a hole inside it, that promise is false — and a false full shield is the same
    // class of lie as a run that reports success while skipping files.

    @Test
    void aProtectedFolderWithAHoleInsideItWearsAHalfShieldNotAFullOne() {
        ProtectedPaths paths = protecting(List.of("/home"), List.of("/home/openhab/userdata/logs"));

        // Still covered — the folder IS part of the backup, so the operator has not lost it...
        assertThat(paths.covers("/home")).isTrue();
        // ...but not whole, so it must not claim to be.
        assertThat(paths.isBackedUp("/home")).isFalse();
        assertThat(paths.containsBackedUp("/home")).isTrue();

        // Every ancestor of the hole is equally holed, all the way down to the hole's own parent.
        assertThat(paths.isBackedUp("/home/openhab")).isFalse();
        assertThat(paths.containsBackedUp("/home/openhab")).isTrue();
        assertThat(paths.isBackedUp("/home/openhab/userdata")).isFalse();
    }

    @Test
    void aFolderWithNoHoleInsideItKeepsItsFullShield() {
        ProtectedPaths paths = protecting(List.of("/home"), List.of("/home/openhab/userdata/logs"));

        // A sibling of the hole, and a folder on another branch entirely, are whole — the hole is not theirs.
        assertThat(paths.isBackedUp("/home/openhab/userdata/logs2")).isTrue();
        assertThat(paths.containsBackedUp("/home/openhab/userdata/logs2")).isFalse();
        assertThat(paths.isBackedUp("/home/geir")).isTrue();
    }

    @Test
    void aFileIsWholeOrItIsNothing_theHoleRuleNeverDemotesOne() {
        // A file has nothing inside it, so the new rule must leave the file verdict exactly as it was: the
        // excluded file is out, every other file under a protected path is fully in.
        ProtectedPaths paths = protecting(List.of("/home"), List.of("/home/openhab/userdata/logs/openhab.log"));

        assertThat(paths.isBackedUp("/home/openhab/userdata/logs/openhab.log")).isFalse();
        assertThat(paths.isBackedUp("/home/geir/notes.txt")).isTrue();
        assertThat(paths.containsBackedUp("/home/geir/notes.txt")).isFalse();
    }

    @Test
    void theTwoShieldsStayMutuallyExclusive() {
        // Whatever the hole rule does, a folder never wears both shields — the Explorer draws one badge.
        ProtectedPaths holed = protecting(List.of("/home"), List.of("/home/openhab/logs"));
        ProtectedPaths whole = protecting(List.of("/home"), List.of());
        ProtectedPaths outside = protecting(List.of("/srv/data"), List.of());

        for (ProtectedPaths paths : List.of(holed, whole, outside)) {
            assertThat(paths.isBackedUp("/home") && paths.containsBackedUp("/home")).isFalse();
        }
        assertThat(outside.isBackedUp("/srv")).isFalse();
        assertThat(outside.containsBackedUp("/srv")).isTrue();
    }

    @Test
    void aFolderWhoseOnlyProtectedContentIsExcludedWearsNoShieldAtAll() {
        // The hole swallows everything: nothing under it reaches an archive, so neither shield applies.
        ProtectedPaths paths = protecting(List.of("/home/openhab/userdata/logs"),
            List.of("/home/openhab/userdata/logs"));

        assertThat(paths.isBackedUp("/home/openhab/userdata")).isFalse();
        assertThat(paths.containsBackedUp("/home/openhab/userdata")).isFalse();
    }

    @Test
    void aGlobExcludeNeverDemotesAFolder_becauseVaierCannotTellWhatItBitesInto() {
        // '*.tmp' is a borg fnmatch pattern, not a path: Vaier cannot say which files it removes, so it can
        // neither claim the folder is holed nor pretend it is whole by path arithmetic. The path rules ignore
        // globs everywhere else (Excludes' own note), and they ignore them here too.
        ProtectedPaths paths = protecting(List.of("/home"), List.of("*.tmp"));

        assertThat(paths.isBackedUp("/home")).isTrue();
        assertThat(paths.containsBackedUp("/home")).isFalse();
    }
}
