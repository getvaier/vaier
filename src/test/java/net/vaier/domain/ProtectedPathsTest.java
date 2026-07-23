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
    void noneProtectsNothing() {
        assertThat(ProtectedPaths.none().isEmpty()).isTrue();
        assertThat(ProtectedPaths.none().covers("/home")).isFalse();
        assertThat(ProtectedPaths.none().enclosesUnder("/home")).isFalse();
    }
}
