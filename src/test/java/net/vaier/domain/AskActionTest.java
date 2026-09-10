package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The <b>Ask action</b> catalogue (#360 slice 2): what the model may propose. Each is a verb the Explorer
 * already has a button for, and none of them runs on the model's say-so — it puts a <b>Confirmation</b> in
 * front of the operator, and the click is what runs it.
 */
class AskActionTest {

    @Test
    void everyActionHasItsPinnedName() {
        assertThat(AskAction.LET_PHONE_IN.toolName()).isEqualTo("let_phone_in");
        assertThat(AskAction.REFUSE_PHONE.toolName()).isEqualTo("refuse_phone");
        assertThat(AskAction.RUN_BACKUP.toolName()).isEqualTo("run_backup");
        assertThat(AskAction.UPDATE_CONTAINER.toolName()).isEqualTo("update_container");
        assertThat(AskAction.LIFT_BLOCK.toolName()).isEqualTo("lift_block");
        assertThat(AskAction.TRUST_ADDRESS.toolName()).isEqualTo("trust_address");
    }

    /** Six verbs the Explorer already has a button for. No restart: Vaier has no such button, on purpose. */
    @Test
    void theCatalogueIsExactlyTheSixVerbsTheExplorerAlreadyHas() {
        assertThat(AskAction.values()).hasSize(6);
    }

    /** An action and a read sharing a name is a request Vaier cannot tell apart. */
    @Test
    void noActionSharesANameWithAnotherActionOrWithARead() {
        Set<String> names = Arrays.stream(AskAction.values()).map(AskAction::toolName).collect(Collectors.toSet());
        Set<String> reads = Arrays.stream(AskTool.values()).map(AskTool::toolName).collect(Collectors.toSet());

        assertThat(names).hasSize(AskAction.values().length);
        assertThat(names).doesNotContainAnyElementsOf(reads);
        assertThat(AskAction.values()).allSatisfy(action -> assertThat(action.toolName()).matches("[a-z]+(_[a-z]+)*"));
    }

    /** The model reads the description to know it is only proposing; every one must say so. */
    @Test
    void everyDescriptionSaysItOnlyProposes() {
        assertThat(AskAction.values()).allSatisfy(action -> {
            assertThat(action.description()).endsWith(".");
            assertThat(action.description()).contains("nothing happens until they click");
        });
    }

    @Test
    void everyActionNamesWhatItNeeds() {
        assertThat(AskAction.LET_PHONE_IN.parameters()).extracting(ToolParameter::name).containsExactly("code");
        assertThat(AskAction.REFUSE_PHONE.parameters()).extracting(ToolParameter::name).containsExactly("code");
        assertThat(AskAction.RUN_BACKUP.parameters()).extracting(ToolParameter::name).containsExactly("machine");
        assertThat(AskAction.UPDATE_CONTAINER.parameters()).extracting(ToolParameter::name)
            .containsExactly("machine", "container");
        assertThat(AskAction.LIFT_BLOCK.parameters()).extracting(ToolParameter::name).containsExactly("address");
        assertThat(AskAction.TRUST_ADDRESS.parameters()).extracting(ToolParameter::name).containsExactly("address");
        assertThat(AskAction.values()).allSatisfy(action ->
            assertThat(action.parameters()).allSatisfy(p -> assertThat(p.description()).isNotBlank()));
    }

    /** The card's sentence is the whole of what the operator reads before clicking, so it is the domain's. */
    @Test
    void theSentenceSaysExactlyWhatTheClickWillDo() {
        assertThat(AskAction.LET_PHONE_IN.sentence(Map.of("code", "4417", "name", "Ruten")))
            .isEqualTo("Let Ruten in (join code 4417).");
        assertThat(AskAction.REFUSE_PHONE.sentence(Map.of("code", "4417", "name", "Ruten")))
            .isEqualTo("Refuse Ruten (join code 4417).");
        assertThat(AskAction.RUN_BACKUP.sentence(Map.of("machine", "Colina 27")))
            .isEqualTo("Back up Colina 27 now.");
        assertThat(AskAction.UPDATE_CONTAINER.sentence(Map.of("machine", "Colina 27", "container", "mosquitto")))
            .isEqualTo("Update mosquitto on Colina 27 to its newer image.");
        assertThat(AskAction.LIFT_BLOCK.sentence(Map.of("address", "203.0.113.9")))
            .isEqualTo("Lift the block on 203.0.113.9.");
        assertThat(AskAction.TRUST_ADDRESS.sentence(Map.of("address", "203.0.113.9")))
            .isEqualTo("Trust 203.0.113.9 from now on.");
    }

    /** Both catalogues are offered to the model through the one shape the adapter knows. */
    @Test
    void anActionIsACapabilityLikeARead() {
        List<AskCapability> both = List.of(AskTool.FLEET, AskAction.RUN_BACKUP);

        assertThat(both).extracting(AskCapability::toolName).containsExactly("fleet", "run_backup");
    }
}
