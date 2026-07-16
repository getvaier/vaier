package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourcePathsTest {

    @Test
    void ofDedupsExactDuplicates() {
        assertThat(SourcePaths.of(List.of("/home/geir", "/home/geir")).paths())
            .containsExactly("/home/geir");
    }

    @Test
    void ofDropsADescendantWhenItsAncestorIsAlsoPresent() {
        // "/home/geir" already covers "/home/geir/docs" — the child is redundant.
        assertThat(SourcePaths.of(List.of("/home/geir", "/home/geir/docs")).paths())
            .containsExactly("/home/geir");
    }

    @Test
    void ofKeepsUnrelatedSiblings() {
        assertThat(SourcePaths.of(List.of("/home/geir", "/etc/nginx")).paths())
            .containsExactlyInAnyOrder("/home/geir", "/etc/nginx");
    }

    @Test
    void ofTrimsWhitespaceAndIgnoresBlanks() {
        assertThat(SourcePaths.of(List.of("  /home/geir  ", "", "   ")).paths())
            .containsExactly("/home/geir");
    }

    @Test
    void ofStripsTrailingSlashSoAncestryStillCollapses() {
        assertThat(SourcePaths.of(List.of("/home/geir/", "/home/geir/docs")).paths())
            .containsExactly("/home/geir");
    }

    @Test
    void ofRejectsARelativePath() {
        assertThatThrownBy(() -> SourcePaths.of(List.of("home/geir")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute");
    }

    @Test
    void rootAncestorSwallowsEverything() {
        assertThat(SourcePaths.of(List.of("/", "/home/geir", "/etc")).paths())
            .containsExactly("/");
    }

    @Test
    void protectingAddsANewSiblingPath() {
        SourcePaths result = SourcePaths.of(List.of("/home/geir")).protecting(List.of("/etc/nginx"));
        assertThat(result.paths()).containsExactlyInAnyOrder("/home/geir", "/etc/nginx");
    }

    @Test
    void protectingWithADescendantOfAnExistingPathIsANoOp() {
        // Adding "/home/geir/docs" when "/home/geir" is present changes nothing (the ancestor covers it).
        SourcePaths result = SourcePaths.of(List.of("/home/geir")).protecting(List.of("/home/geir/docs"));
        assertThat(result.paths()).containsExactly("/home/geir");
    }

    @Test
    void protectingWithAnAncestorReplacesTheNarrowerChild() {
        // Adding "/home" when "/home/geir" is present replaces the child with the broader "/home".
        SourcePaths result = SourcePaths.of(List.of("/home/geir")).protecting(List.of("/home"));
        assertThat(result.paths()).containsExactly("/home");
    }

    @Test
    void withoutRemovesAnExactPath() {
        SourcePaths result = SourcePaths.of(List.of("/home/geir", "/etc/nginx")).without(List.of("/home/geir"));
        assertThat(result.paths()).containsExactly("/etc/nginx");
    }

    @Test
    void withoutRemovesDescendantsOfARemovedPath() {
        // Removing "/home/geir" clears "/home/geir/docs" too.
        SourcePaths result = SourcePaths.of(List.of("/home/geir/docs", "/home/geir/photos", "/etc"))
            .without(List.of("/home/geir"));
        assertThat(result.paths()).containsExactly("/etc");
    }

    @Test
    void withoutEverythingLeavesAnEmptySet() {
        SourcePaths result = SourcePaths.of(List.of("/home/geir")).without(List.of("/home/geir"));
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.paths()).isEmpty();
    }

    @Test
    void withoutAPathThatIsNotPresentIsANoOp() {
        SourcePaths result = SourcePaths.of(List.of("/home/geir")).without(List.of("/var/log"));
        assertThat(result.paths()).containsExactly("/home/geir");
    }

    @Test
    void coversAnExactMember() {
        assertThat(SourcePaths.of(List.of("/home/geir")).covers("/home/geir")).isTrue();
    }

    @Test
    void coversADescendantOfAMember() {
        assertThat(SourcePaths.of(List.of("/home/geir")).covers("/home/geir/docs/notes.txt")).isTrue();
    }

    @Test
    void doesNotCoverAnUnrelatedPath() {
        assertThat(SourcePaths.of(List.of("/home/geir")).covers("/etc/nginx")).isFalse();
    }

    @Test
    void doesNotCoverAnAncestorOfAMember() {
        // "/home" is NOT backed up just because "/home/geir" is — coverage flows down, not up.
        assertThat(SourcePaths.of(List.of("/home/geir")).covers("/home")).isFalse();
    }

    @Test
    void doesNotCoverASiblingWithASharedNamePrefix() {
        // "/home/geir2" must not be treated as inside "/home/geir".
        assertThat(SourcePaths.of(List.of("/home/geir")).covers("/home/geir2")).isFalse();
    }

    @Test
    void anEmptySetCoversNothing() {
        assertThat(SourcePaths.of(List.of()).covers("/home/geir")).isFalse();
    }

    @Test
    void enclosesUnderAnAncestorOfAMember() {
        // "/home" is not itself backed up, but "/home/geir" lives inside it.
        assertThat(SourcePaths.of(List.of("/home/geir")).enclosesUnder("/home")).isTrue();
    }

    @Test
    void doesNotEncloseAMemberItself() {
        // "/home/geir" IS the source path — it is covered, not merely enclosing.
        assertThat(SourcePaths.of(List.of("/home/geir")).enclosesUnder("/home/geir")).isFalse();
    }

    @Test
    void doesNotEncloseADescendantOfAMember() {
        assertThat(SourcePaths.of(List.of("/home/geir")).enclosesUnder("/home/geir/docs")).isFalse();
    }

    @Test
    void doesNotEncloseAnUnrelatedPath() {
        assertThat(SourcePaths.of(List.of("/home/geir")).enclosesUnder("/var")).isFalse();
    }

    @Test
    void doesNotEncloseASiblingWithASharedNamePrefix() {
        assertThat(SourcePaths.of(List.of("/home/geir")).enclosesUnder("/home/gei")).isFalse();
    }

    @Test
    void anEmptySetEnclosesNothing() {
        assertThat(SourcePaths.of(List.of()).enclosesUnder("/home")).isFalse();
    }
}
