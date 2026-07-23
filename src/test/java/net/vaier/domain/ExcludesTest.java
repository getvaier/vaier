package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcludesTest {

    @Test
    void ofDedupsExactDuplicates() {
        assertThat(Excludes.of(List.of("/home/openhab/logs", "/home/openhab/logs")).patterns())
            .containsExactly("/home/openhab/logs");
    }

    @Test
    void ofDropsAnExcludeAnotherExcludeAlreadyCovers() {
        // Excluding "/home/openhab" already keeps everything under it out; the deeper entry is noise.
        assertThat(Excludes.of(List.of("/home/openhab", "/home/openhab/logs")).patterns())
            .containsExactly("/home/openhab");
    }

    @Test
    void ofTrimsBlanksAndTrailingSlashesSoAncestryStillCollapses() {
        assertThat(Excludes.of(List.of(" /home/openhab/ ", "", "  ", "/home/openhab/logs")).patterns())
            .containsExactly("/home/openhab");
    }

    @Test
    void ofKeepsAGlobPatternVerbatim() {
        // The job editor may set borg fnmatch patterns. They are not paths, so containment says nothing about
        // them — they are carried through untouched rather than "normalized" into something borg would not honour.
        assertThat(Excludes.of(List.of("*.tmp", "/home/openhab")).patterns())
            .containsExactlyInAnyOrder("*.tmp", "/home/openhab");
    }

    @Test
    void excludesThePathItselfAndEverythingUnderIt() {
        Excludes excludes = Excludes.of(List.of("/home/openhab/userdata/logs"));

        assertThat(excludes.excludes("/home/openhab/userdata/logs")).isTrue();
        assertThat(excludes.excludes("/home/openhab/userdata/logs/openhab.log")).isTrue();
        assertThat(excludes.excludes("/home/openhab/userdata")).isFalse();
        assertThat(excludes.excludes("/home/openhab/userdata/logs2")).isFalse();
    }

    @Test
    void excludingAddsAPathAndReNormalizes() {
        Excludes excludes = Excludes.of(List.of("/home/openhab/userdata/logs"))
            .excluding(List.of("/home/openhab/userdata"));

        assertThat(excludes.patterns()).containsExactly("/home/openhab/userdata");
    }

    @Test
    void clearedForDropsAnExcludeTheNewlyProtectedPathCovers() {
        // "Back up /home/openhab/userdata" must not leave a stale exclude silently skipping its logs folder.
        Excludes excludes = Excludes.of(List.of("/home/openhab/userdata/logs"))
            .clearedFor(List.of("/home/openhab/userdata"));

        assertThat(excludes.patterns()).isEmpty();
    }

    @Test
    void clearedForDropsAnExcludeThatWouldSwallowTheNewlyProtectedPath() {
        // The exclude is an ANCESTOR of what was just explicitly protected. Keeping it would silently skip the
        // very folder the operator just asked for, so the newer instruction wins and the exclude goes.
        Excludes excludes = Excludes.of(List.of("/home/openhab"))
            .clearedFor(List.of("/home/openhab/userdata/logs"));

        assertThat(excludes.patterns()).isEmpty();
    }

    @Test
    void clearedForKeepsAnUnrelatedExclude() {
        Excludes excludes = Excludes.of(List.of("/home/openhab/userdata/logs", "*.tmp"))
            .clearedFor(List.of("/etc/nginx"));

        assertThat(excludes.patterns()).containsExactlyInAnyOrder("/home/openhab/userdata/logs", "*.tmp");
    }

    @Test
    void prunedToDropsAnExcludeNoSourcePathCoversAnyMore() {
        // /home stopped being backed up, so an exclude carving a hole inside it means nothing any more.
        Excludes excludes = Excludes.of(List.of("/home/openhab/userdata/logs"))
            .prunedTo(SourcePaths.of(List.of("/etc/nginx")));

        assertThat(excludes.patterns()).isEmpty();
    }

    @Test
    void prunedToKeepsAnExcludeStillInsideAProtectedPath() {
        Excludes excludes = Excludes.of(List.of("/home/openhab/userdata/logs"))
            .prunedTo(SourcePaths.of(List.of("/home")));

        assertThat(excludes.patterns()).containsExactly("/home/openhab/userdata/logs");
    }

    @Test
    void prunedToNeverDropsAGlobPattern() {
        // A pattern is not a path: Vaier cannot tell which source path it bites into, so it is never pruned.
        Excludes excludes = Excludes.of(List.of("*.tmp")).prunedTo(SourcePaths.of(List.of("/etc/nginx")));

        assertThat(excludes.patterns()).containsExactly("*.tmp");
    }

    @Test
    void noneIsEmpty() {
        assertThat(Excludes.none().patterns()).isEmpty();
        assertThat(Excludes.none().isEmpty()).isTrue();
    }
}
