package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The <b>Chat action</b> catalogue (#360 slice 2): what the model may propose. Each is a verb the Explorer
 * already has a button for, and none of them runs on the model's say-so — it puts a <b>Confirmation</b> in
 * front of the operator, and the click is what runs it.
 */
class ChatActionTest {

    @Test
    void everyActionHasItsPinnedName() {
        assertThat(ChatAction.LET_PHONE_IN.toolName()).isEqualTo("let_phone_in");
        assertThat(ChatAction.REFUSE_PHONE.toolName()).isEqualTo("refuse_phone");
        assertThat(ChatAction.RUN_BACKUP.toolName()).isEqualTo("run_backup");
        assertThat(ChatAction.UPDATE_CONTAINER.toolName()).isEqualTo("update_container");
        assertThat(ChatAction.LIFT_BLOCK.toolName()).isEqualTo("lift_block");
        assertThat(ChatAction.TRUST_ADDRESS.toolName()).isEqualTo("trust_address");
    }

    /** Six verbs the Explorer already has a button for. No restart: Vaier has no such button, on purpose. */
    @Test
    void theCatalogueIsExactlyTheSixVerbsTheExplorerAlreadyHas() {
        assertThat(ChatAction.values()).hasSize(6);
    }

    /** An action and a read sharing a name is a request Vaier cannot tell apart. */
    @Test
    void noActionSharesANameWithAnotherActionOrWithARead() {
        Set<String> names = Arrays.stream(ChatAction.values()).map(ChatAction::toolName).collect(Collectors.toSet());
        Set<String> reads = Arrays.stream(ChatTool.values()).map(ChatTool::toolName).collect(Collectors.toSet());

        assertThat(names).hasSize(ChatAction.values().length);
        assertThat(names).doesNotContainAnyElementsOf(reads);
        assertThat(ChatAction.values()).allSatisfy(action -> assertThat(action.toolName()).matches("[a-z]+(_[a-z]+)*"));
    }

    /** The model reads the description to know it is only proposing; every one must say so. */
    @Test
    void everyDescriptionSaysItOnlyProposes() {
        assertThat(ChatAction.values()).allSatisfy(action -> {
            assertThat(action.description()).endsWith(".");
            assertThat(action.description()).contains("nothing happens until they click");
        });
    }

    @Test
    void everyActionNamesWhatItNeeds() {
        assertThat(ChatAction.LET_PHONE_IN.parameters()).extracting(ToolParameter::name).containsExactly("code");
        assertThat(ChatAction.REFUSE_PHONE.parameters()).extracting(ToolParameter::name).containsExactly("code");
        assertThat(ChatAction.RUN_BACKUP.parameters()).extracting(ToolParameter::name).containsExactly("machine");
        assertThat(ChatAction.UPDATE_CONTAINER.parameters()).extracting(ToolParameter::name)
            .containsExactly("machine", "container");
        assertThat(ChatAction.LIFT_BLOCK.parameters()).extracting(ToolParameter::name).containsExactly("address");
        assertThat(ChatAction.TRUST_ADDRESS.parameters()).extracting(ToolParameter::name).containsExactly("address");
        assertThat(ChatAction.values()).allSatisfy(action ->
            assertThat(action.parameters()).allSatisfy(p -> assertThat(p.description()).isNotBlank()));
    }

    /** The card's sentence is the whole of what the operator reads before clicking, so it is the domain's. */
    @Test
    void theSentenceSaysExactlyWhatTheClickWillDo() {
        assertThat(ChatAction.LET_PHONE_IN.sentence(Map.of("code", "4417", "name", "Ruten")))
            .isEqualTo("Let Ruten in (join code 4417).");
        assertThat(ChatAction.REFUSE_PHONE.sentence(Map.of("code", "4417", "name", "Ruten")))
            .isEqualTo("Refuse Ruten (join code 4417).");
        assertThat(ChatAction.RUN_BACKUP.sentence(Map.of("machine", "Colina 27")))
            .isEqualTo("Back up Colina 27 now.");
        assertThat(ChatAction.UPDATE_CONTAINER.sentence(Map.of("machine", "Colina 27", "container", "mosquitto")))
            .isEqualTo("Update mosquitto on Colina 27 to its newer image.");
        assertThat(ChatAction.LIFT_BLOCK.sentence(Map.of("address", "203.0.113.9")))
            .isEqualTo("Lift the block on 203.0.113.9.");
        assertThat(ChatAction.TRUST_ADDRESS.sentence(Map.of("address", "203.0.113.9")))
            .isEqualTo("Trust 203.0.113.9 from now on.");
    }

    /** Both catalogues are offered to the model through the one shape the adapter knows. */
    @Test
    void anActionIsACapabilityLikeARead() {
        List<ChatCapability> both = List.of(ChatTool.FLEET, ChatAction.RUN_BACKUP);

        assertThat(both).extracting(ChatCapability::toolName).containsExactly("fleet", "run_backup");
    }
}
