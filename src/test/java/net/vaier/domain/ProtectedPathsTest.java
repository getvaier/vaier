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
    }
}
