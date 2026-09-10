package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The <b>Ask tool</b> catalogue (#360). Every name here is pinned: the model learns a tool by its name, and
 * a name that drifts between releases silently turns a working conversation into "I have no tool for that".
 */
class AskToolTest {

    @Test
    void everyToolHasItsPinnedName() {
        assertThat(AskTool.FLEET.toolName()).isEqualTo("fleet");
        assertThat(AskTool.WAITING_TO_JOIN.toolName()).isEqualTo("waiting_to_join");
        assertThat(AskTool.PUBLISHED_SERVICES.toolName()).isEqualTo("published_services");
        assertThat(AskTool.BACKUPS.toolName()).isEqualTo("backups");
        assertThat(AskTool.DISKS.toolName()).isEqualTo("disks");
        assertThat(AskTool.CONTAINER_UPDATES.toolName()).isEqualTo("container_updates");
        assertThat(AskTool.SECURITY.toolName()).isEqualTo("security");
        assertThat(AskTool.RUN_ON_MACHINE.toolName()).isEqualTo("run_on_machine");
    }

    /** Seven whole-fleet reads and one command run, and no ninth that nobody wrote a read for. */
    @Test
    void theCatalogueIsExactlyTheSevenReadsOfSliceOnePlusTheCommandRun() {
        assertThat(AskTool.values()).hasSize(8);
    }

    /**
     * The whole-fleet reads take nothing, so none of them can be talked into reading something it was not
     * offered. The command run is the one tool that takes an argument, and it takes exactly two: which
     * machine, and what to run — the command itself is judged by {@code ReadOnlyCommand} before it runs.
     */
    @Test
    void onlyTheCommandRunTakesArguments_andItTakesExactlyTwo() {
        for (AskTool tool : AskTool.values()) {
            if (tool != AskTool.RUN_ON_MACHINE) {
                assertThat(tool.parameters()).as(tool.toolName()).isEmpty();
            }
        }
        assertThat(AskTool.RUN_ON_MACHINE.parameters()).extracting(ToolParameter::name)
            .containsExactly("machine", "command");
        assertThat(AskTool.RUN_ON_MACHINE.parameters()).allSatisfy(parameter ->
            assertThat(parameter.description()).isNotBlank());
    }

    /** The model reads the description to decide what to run, so it must say what will be refused. */
    @Test
    void theCommandRunSaysItOnlyLooks_andWithoutSudo() {
        assertThat(AskTool.RUN_ON_MACHINE.description())
            .contains("SSH").contains("without sudo").contains(ReadOnlyCommand.WHAT_IS_ALLOWED);
    }

    /** Two tools sharing a name is a request the model can send that Vaier cannot answer. */
    @Test
    void noTwoToolsShareAName() {
        Set<String> names = Arrays.stream(AskTool.values()).map(AskTool::toolName)
            .collect(java.util.stream.Collectors.toSet());

        assertThat(names).hasSize(AskTool.values().length);
    }

    /** snake_case, because that is what every tool name in the catalogue already is and mixing the two costs a round trip. */
    @Test
    void everyToolNameIsLowerCaseSnakeCase() {
        assertThat(AskTool.values()).allSatisfy(tool ->
            assertThat(tool.toolName()).matches("[a-z]+(_[a-z]+)*"));
    }

    /** The model picks a tool by reading its description, so an empty one is a tool it will never call. */
    @Test
    void everyToolSaysInOneSentenceWhatItAnswers() {
        List<AskTool> tools = List.of(AskTool.values());

        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.description()).endsWith(".");
        });
    }

    @Test
    void theDescriptionsSayWhatEachReadAnswers() {
        assertThat(AskTool.FLEET.description())
            .isEqualTo("Every machine in the fleet, with its name, what kind of machine it is, "
                + "its tunnel address and whether it is connected right now.");
        assertThat(AskTool.WAITING_TO_JOIN.description())
            .isEqualTo("The phones waiting to be let into the fleet, with the join code each one is "
                + "showing and how many minutes it has left.");
        assertThat(AskTool.SECURITY.description())
            .isEqualTo("Who is being kept out of the fleet's edge right now, and why.");
    }
}
