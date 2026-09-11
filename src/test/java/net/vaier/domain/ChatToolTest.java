package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The <b>Chat tool</b> catalogue (#360). Every name here is pinned: the model learns a tool by its name, and
 * a name that drifts between releases silently turns a working conversation into "I have no tool for that".
 */
class ChatToolTest {

    @Test
    void everyToolHasItsPinnedName() {
        assertThat(ChatTool.FLEET.toolName()).isEqualTo("fleet");
        assertThat(ChatTool.WAITING_TO_JOIN.toolName()).isEqualTo("waiting_to_join");
        assertThat(ChatTool.PUBLISHED_SERVICES.toolName()).isEqualTo("published_services");
        assertThat(ChatTool.BACKUPS.toolName()).isEqualTo("backups");
        assertThat(ChatTool.DISKS.toolName()).isEqualTo("disks");
        assertThat(ChatTool.CONTAINER_UPDATES.toolName()).isEqualTo("container_updates");
        assertThat(ChatTool.SECURITY.toolName()).isEqualTo("security");
        assertThat(ChatTool.RUN_ON_MACHINE.toolName()).isEqualTo("run_on_machine");
        assertThat(ChatTool.BUNDLE_FILES.toolName()).isEqualTo("bundle_files");
        assertThat(ChatTool.REMEMBER.toolName()).isEqualTo("remember");
        assertThat(ChatTool.FORGET.toolName()).isEqualTo("forget");
        assertThat(ChatTool.EMAIL_BUNDLE.toolName()).isEqualTo("email_bundle");
        assertThat(ChatTool.SEARCH_WEB.toolName()).isEqualTo("search_web");
        assertThat(ChatTool.READ_WEB_PAGE.toolName()).isEqualTo("read_web_page");
        assertThat(ChatTool.ADD_ERRAND.toolName()).isEqualTo("add_errand");
        assertThat(ChatTool.CANCEL_ERRAND.toolName()).isEqualTo("cancel_errand");
    }

    /**
     * Seven whole-fleet reads, the command run, the bundle and its mail, the two memory verbs, the two
     * halves of the <b>web read</b>, and the two <b>errand</b> verbs.
     */
    @Test
    void theCatalogueIsExactlySixteenTools() {
        assertThat(ChatTool.values()).hasSize(16);
    }

    // --- the errand verbs (#360 slice 2) -------------------------------------------------------------

    @Test
    void addingAnErrandTakesTheInstructionAndTheRhythm() {
        assertThat(ChatTool.ADD_ERRAND.parameters()).extracting(ToolParameter::name, ToolParameter::many)
            .containsExactly(tuple("instruction", false), tuple("rhythm", false));
        assertThat(ChatTool.CANCEL_ERRAND.parameters()).extracting(ToolParameter::name).containsExactly("id");
    }

    /**
     * The rhythm is one string the model writes from memory, so the description spells out all four shapes
     * verbatim — a shape it has to guess at is a refusal and a wasted turn.
     */
    @Test
    void addingAnErrandSpellsOutEveryRhythmShape() {
        assertThat(ChatTool.ADD_ERRAND.parameters().get(1).description())
            .contains("once 2026-09-12T08:00")
            .contains("daily 08:00")
            .contains("weekly monday 08:00")
            .contains("monthly 1 08:00");
    }

    /** An errand runs with nobody watching, so the instruction has to stand on its own. */
    @Test
    void addingAnErrandSaysTheInstructionIsWrittenForMarvinHimself() {
        assertThat(ChatTool.ADD_ERRAND.description())
            .contains("mails")
            .contains("never on your own initiative");
        assertThat(ChatTool.ADD_ERRAND.parameters().get(0).description())
            .contains("self-contained");
        assertThat(ChatTool.CANCEL_ERRAND.description()).contains("only when the operator asks");
    }

    /**
     * Which reads Marvin may make with nobody watching is the domain's decision, not the assembler's. The
     * four left out are the ones that need somebody there: a download card nobody would click, a mailed link
     * to a bundle nobody offered, and the two errand verbs — Marvin never sends himself on an errand.
     */
    @Test
    void theReadsMarvinMayMakeAloneLeaveOutEverythingThatNeedsSomebodyThere() {
        assertThat(ChatTool.whileNobodyIsWatching()).containsExactly(
            ChatTool.FLEET, ChatTool.WAITING_TO_JOIN, ChatTool.PUBLISHED_SERVICES, ChatTool.BACKUPS,
            ChatTool.DISKS, ChatTool.CONTAINER_UPDATES, ChatTool.SECURITY, ChatTool.RUN_ON_MACHINE,
            ChatTool.SEARCH_WEB, ChatTool.READ_WEB_PAGE, ChatTool.REMEMBER, ChatTool.FORGET);
        assertThat(ChatTool.whileNobodyIsWatching()).doesNotContain(
            ChatTool.BUNDLE_FILES, ChatTool.EMAIL_BUNDLE, ChatTool.ADD_ERRAND, ChatTool.CANCEL_ERRAND);
    }

    /** One takes a few words, the other one address — and nothing else, so neither can be widened. */
    @Test
    void theWebReadTakesAQueryAndAnAddress() {
        assertThat(ChatTool.SEARCH_WEB.parameters()).extracting(ToolParameter::name, ToolParameter::many)
            .containsExactly(tuple("query", false));
        assertThat(ChatTool.READ_WEB_PAGE.parameters()).extracting(ToolParameter::name, ToolParameter::many)
            .containsExactly(tuple("url", false));
    }

    /**
     * The descriptions are the model's only guide to when the internet is the right place to look: for what
     * the fleet cannot tell it, and never for what the fleet can.
     */
    @Test
    void theWebReadSaysWhenToUseTheInternet_andThatItIsOnlyThePublicOne() {
        assertThat(ChatTool.SEARCH_WEB.description())
            .contains("what the fleet cannot tell you")
            .contains("read_web_page")
            .contains("only the public internet");
        assertThat(ChatTool.READ_WEB_PAGE.description())
            .contains("one public web page")
            .contains("only the public internet")
            .contains("the fleet's own addresses are refused");
    }

    @Test
    void mailingABundleTakesItsId_andOnlyOnceTheOperatorSaidSo() {
        assertThat(ChatTool.EMAIL_BUNDLE.parameters()).extracting(ToolParameter::name).containsExactly("id");
        assertThat(ChatTool.EMAIL_BUNDLE.description()).contains("only once they have said");
    }

    @Test
    void theMemoryVerbsTakeAFactAndAnId() {
        assertThat(ChatTool.REMEMBER.parameters()).extracting(ToolParameter::name).containsExactly("fact");
        assertThat(ChatTool.FORGET.parameters()).extracting(ToolParameter::name).containsExactly("id");
        assertThat(ChatTool.REMEMBER.description()).contains("across conversations").contains("never an instruction")
            .contains("in the same turn and without being asked");
    }

    /** The bundle names the machine, the files one per line, and what to call the zip. */
    @Test
    void theBundleTakesTheMachine_thePathsAsAList_andAName() {
        assertThat(ChatTool.BUNDLE_FILES.parameters()).extracting(ToolParameter::name, ToolParameter::many)
            .containsExactly(tuple("machine", false),
                tuple("paths", true),
                tuple("name", false));
        assertThat(ChatTool.BUNDLE_FILES.description()).contains("download card").contains("Nothing is copied");
    }

    /**
     * The whole-fleet reads take nothing, so none of them can be talked into reading something it was not
     * offered. The command run is the one tool that takes an argument, and it takes exactly two: which
     * machine, and what to run — the command itself is judged by {@code ReadOnlyCommand} before it runs.
     */
    @Test
    void theWholeFleetReadsTakeNothing_andTheCommandRunTakesExactlyTwo() {
        for (ChatTool tool : ChatTool.values()) {
            if (tool.parameters().isEmpty()) {
                assertThat(tool.parameters()).as(tool.toolName()).isEmpty();
            }
        }
        assertThat(ChatTool.RUN_ON_MACHINE.parameters()).extracting(ToolParameter::name)
            .containsExactly("machine", "command");
        assertThat(ChatTool.RUN_ON_MACHINE.parameters()).allSatisfy(parameter ->
            assertThat(parameter.description()).isNotBlank());
    }

    /** The model reads the description to decide what to run, so it must say what will be refused. */
    @Test
    void theCommandRunSaysItOnlyLooks_andWithoutSudo() {
        assertThat(ChatTool.RUN_ON_MACHINE.description())
            .contains("SSH").contains("without sudo").contains(ReadOnlyCommand.WHAT_IS_ALLOWED);
    }

    /** Two tools sharing a name is a request the model can send that Vaier cannot answer. */
    @Test
    void noTwoToolsShareAName() {
        Set<String> names = Arrays.stream(ChatTool.values()).map(ChatTool::toolName)
            .collect(java.util.stream.Collectors.toSet());

        assertThat(names).hasSize(ChatTool.values().length);
    }

    /** snake_case, because that is what every tool name in the catalogue already is and mixing the two costs a round trip. */
    @Test
    void everyToolNameIsLowerCaseSnakeCase() {
        assertThat(ChatTool.values()).allSatisfy(tool ->
            assertThat(tool.toolName()).matches("[a-z]+(_[a-z]+)*"));
    }

    /** The model picks a tool by reading its description, so an empty one is a tool it will never call. */
    @Test
    void everyToolSaysInOneSentenceWhatItAnswers() {
        List<ChatTool> tools = List.of(ChatTool.values());

        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.description()).endsWith(".");
        });
    }

    @Test
    void theDescriptionsSayWhatEachReadAnswers() {
        assertThat(ChatTool.FLEET.description())
            .isEqualTo("Every machine in the fleet, with its name, what kind of machine it is, "
                + "its tunnel address and whether it is connected right now.");
        assertThat(ChatTool.WAITING_TO_JOIN.description())
            .isEqualTo("The phones waiting to be let into the fleet, with the join code each one is "
                + "showing and how many minutes it has left.");
        assertThat(ChatTool.SECURITY.description())
            .isEqualTo("Who is being kept out of the fleet's edge right now, and why.");
    }
}
