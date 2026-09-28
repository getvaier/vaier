package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
        assertThat(ChatAction.UPGRADE_OS.toolName()).isEqualTo("upgrade_os");
        assertThat(ChatAction.CALL_SERVICE.toolName()).isEqualTo("call_service");
    }

    /**
     * Seven verbs the Explorer already has a button for, and a write to a published service's own API, whose
     * button is the service's own UI. No restart: Vaier has no such button, on purpose.
     */
    @Test
    void theCatalogueIsExactlyTheSevenExplorerVerbsAndAServiceCall() {
        assertThat(ChatAction.values()).hasSize(8);
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
            assertThat(action.description()).contains("nothing happens until they say yes");
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
        assertThat(ChatAction.UPGRADE_OS.parameters()).extracting(ToolParameter::name).containsExactly("machine");
        assertThat(ChatAction.CALL_SERVICE.parameters()).extracting(ToolParameter::name, ToolParameter::optional)
            .containsExactly(tuple("service", false), tuple("method", false), tuple("path", false),
                tuple("body", true), tuple("headline", false));
        assertThat(ChatAction.values()).allSatisfy(action ->
            assertThat(action.parameters()).allSatisfy(p -> assertThat(p.description()).isNotBlank()));
    }

    /**
     * The card's wording is the domain's: a plain headline a novice can read, and under it the exact facts an
     * expert checks. Neither part may be dropped.
     */
    @Test
    void theWordingIsAPlainHeadlineOverTheExactDetails() {
        record Row(ChatAction action, Map<String, String> arguments, String headline, String details) {}
        Map<String, String> phone = Map.of("code", "4417", "name", "Ruten");
        Map<String, String> colina = Map.of("machine", "Colina 27");
        Map<String, String> address = Map.of("address", "203.0.113.9");
        for (Row row : new Row[] {
            new Row(ChatAction.LET_PHONE_IN, phone, "Let Ruten join your network.", "Join code 4417."),
            new Row(ChatAction.REFUSE_PHONE, phone, "Turn Ruten away.",
                "Join code 4417. Its request to join disappears."),
            new Row(ChatAction.RUN_BACKUP, colina, "Back up Colina 27 now.", "With the backup job it already has."),
            new Row(ChatAction.UPDATE_CONTAINER, Map.of("machine", "Colina 27", "container", "mosquitto"),
                "Update mosquitto on Colina 27 to its latest version.",
                "Container mosquitto gets the newer image its registry serves, and is down for a moment while it "
                    + "restarts."),
            new Row(ChatAction.LIFT_BLOCK, address, "Let 203.0.113.9 reach your services again.",
                "It is blocked right now. This lifts the block once; it can still be blocked again later."),
            new Row(ChatAction.TRUST_ADDRESS, address, "Always let 203.0.113.9 in, and never block it.",
                "It becomes a trusted address."),
            new Row(ChatAction.UPGRADE_OS, colina, "Install the system updates on Colina 27.",
                "The pending OS package updates, installed with apt or dnf. Vaier does not restart it."),
            new Row(ChatAction.CALL_SERVICE, Map.of("service", "openhab on Colina 27", "method", "POST",
                "path", "/rest/items/PoolPump", "body", "ON", "headline", " Turn on the pool pump at Colina 27. "),
                "Turn on the pool pump at Colina 27.",
                ServiceCall.proposed("POST", "/rest/items/PoolPump", "ON").details("openhab on Colina 27")),
        }) {
            assertThat(row.action().wording(row.arguments())).as(row.action().name())
                .isEqualTo(new ActionWording(row.headline(), row.details()));
        }
    }

    /**
     * A service call's headline is the model's own words, so it is judged: it must say something, and briefly.
     * The details under it are what catch a headline that misleads.
     */
    @Test
    void aServiceCallsHeadlineMustBeSaid_andShort() {
        for (String headline : new String[] { null, "  ", "x".repeat(ActionWording.MAX_HEADLINE_CHARS + 1) }) {
            Map<String, String> arguments = new HashMap<>(Map.of("service", "openhab on Colina 27",
                "method", "DELETE", "path", "/rest/items/PoolPump"));
            arguments.put("headline", headline);
            assertThatThrownBy(() -> ChatAction.CALL_SERVICE.wording(arguments)).as(String.valueOf(headline))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("everyday words");
        }
    }

    /** What the pane says once a yes is under way, in the same two parts. */
    @Test
    void theStartedWordingSaysWhatIsUnderway() {
        record Row(ChatAction action, Map<String, String> arguments, ActionWording said) {}
        Map<String, String> colina = Map.of("machine", "Colina 27");
        for (Row row : new Row[] {
            new Row(ChatAction.LET_PHONE_IN, Map.of("code", "4417", "name", "Ruten"),
                new ActionWording("Ruten can join your network now.", null)),
            new Row(ChatAction.REFUSE_PHONE, Map.of("code", "4417", "name", "Ruten"),
                new ActionWording("Turned Ruten away.", null)),
            new Row(ChatAction.RUN_BACKUP, colina,
                new ActionWording("Backing up Colina 27 now.", "The Backups pane shows how it goes.")),
            new Row(ChatAction.UPDATE_CONTAINER, Map.of("machine", "Colina 27", "container", "mosquitto"),
                new ActionWording("Updating mosquitto on Colina 27.", "It is down for a moment while it restarts.")),
            new Row(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"),
                new ActionWording("203.0.113.9 can reach your services again.", null)),
            new Row(ChatAction.TRUST_ADDRESS, Map.of("address", "203.0.113.9"),
                new ActionWording("203.0.113.9 is always let in from now on.", null)),
            new Row(ChatAction.UPGRADE_OS, colina, new ActionWording("Installing the system updates on Colina 27.",
                "Vaier says how it went when it is done.")),
        }) {
            assertThat(row.action().started(row.arguments())).as(row.action().name()).isEqualTo(row.said());
        }
    }

    /** Both catalogues are offered to the model through the one shape the adapter knows. */
    @Test
    void anActionIsACapabilityLikeARead() {
        List<ChatCapability> both = List.of(ChatTool.FLEET, ChatAction.RUN_BACKUP);

        assertThat(both).extracting(ChatCapability::toolName).containsExactly("fleet", "run_backup");
    }
}
