package net.vaier.domain;

import net.vaier.domain.Selection.Coordinate;
import net.vaier.domain.Selection.Placement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@link Selection} — the set of file coordinates the operator picked in the Explorer's selection bar,
 * to act on together. It owns the arrangement decision for a "download the whole selection as one zip":
 * the download's filename, and each coordinate's top-level zip-entry name. The service only orchestrates
 * (resolve, stat, walk, stream) — the naming and de-duping rules live here.
 */
class SelectionTest {

    private static Coordinate on(String machine, String path) {
        return new Coordinate(machine, path, null);
    }

    @Test
    void aSelectionOnOneMachine_isNamedAfterThatMachine() {
        Selection selection = new Selection(List.of(
            on("apalveien5", "/home/geir/notes.txt"),
            on("apalveien5", "/etc/hosts")));

        assertThat(selection.spansMultipleMachines()).isFalse();
        assertThat(selection.downloadFilename()).isEqualTo("apalveien5.zip");
    }

    @Test
    void aSelectionSpanningMachines_isTheGenericSelectionName() {
        Selection selection = new Selection(List.of(
            on("apalveien5", "/home/geir/notes.txt"),
            on("colina27", "/etc/hosts")));

        assertThat(selection.spansMultipleMachines()).isTrue();
        assertThat(selection.downloadFilename()).isEqualTo("vaier-selection.zip");
    }

    @Test
    void onOneMachine_eachCoordinateIsATopLevelEntryNamedByItsBasename() {
        Selection selection = new Selection(List.of(
            on("apalveien5", "/home/geir/notes.txt"),
            on("apalveien5", "/var/log")));

        assertThat(selection.placements()).extracting(Placement::entryPrefix)
            .containsExactly("notes.txt", "log");
    }

    @Test
    void spanningMachines_everyEntryIsPrefixedByItsMachine_soTwoMachinesEtcDoNotCollide() {
        Selection selection = new Selection(List.of(
            on("apalveien5", "/etc"),
            on("colina27", "/etc")));

        // The machine prefix is the namespace: two machines' /etc keep their own top-level folder.
        assertThat(selection.placements()).extracting(Placement::entryPrefix)
            .containsExactly("apalveien5/etc", "colina27/etc");
    }

    @Test
    void basenameCollisionsWithinOneNamespace_areDeDupedWithASuffix_neverSilentlyOverwritten() {
        Selection selection = new Selection(List.of(
            on("apalveien5", "/a/config.yml"),
            on("apalveien5", "/b/config.yml"),
            on("apalveien5", "/c/config.yml")));

        assertThat(selection.placements()).extracting(Placement::entryPrefix)
            .containsExactly("config.yml", "config.yml (2)", "config.yml (3)");
    }

    @Test
    void aBasenameCollisionAcrossMachines_doesNotDeDup_becauseTheMachinePrefixAlreadySeparatesThem() {
        Selection selection = new Selection(List.of(
            on("apalveien5", "/a/config.yml"),
            on("colina27", "/b/config.yml")));

        assertThat(selection.placements()).extracting(Placement::entryPrefix)
            .containsExactly("apalveien5/config.yml", "colina27/config.yml");
    }

    @Test
    void aRootCoordinate_hasNoBasenameOfItsOwn_soItsMachineNameStandsIn() {
        Selection selection = new Selection(List.of(on("apalveien5", "/")));

        assertThat(selection.placements()).extracting(Placement::entryPrefix)
            .containsExactly("apalveien5");
    }

    @Test
    void anArchiveCoordinate_carriesItsPointInTime_andIsNamedTheSameWay() {
        Coordinate past = new Coordinate("apalveien5", "/home/geir/notes.txt", "ab12");
        Selection selection = new Selection(List.of(past));

        assertThat(selection.placements().getFirst().coordinate().at()).isEqualTo("ab12");
        assertThat(selection.placements().getFirst().entryPrefix()).isEqualTo("notes.txt");
    }

    @Test
    void anEmptySelection_isNotASelection() {
        assertThatThrownBy(() -> new Selection(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
