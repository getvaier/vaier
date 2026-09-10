package net.vaier.domain;

import java.util.List;
import java.util.Map;

/**
 * The <b>Ask action</b> catalogue (#360 slice 2): what the model may propose. Each is a verb the Explorer
 * already has a button for, and none of them runs on the model's say-so — calling one puts a
 * <b>Confirmation</b> in front of the operator, and their click is what runs it.
 *
 * <p>There is no restart here, deliberately: Vaier has no button to start, stop or restart a container,
 * and an action Ask can propose must be one the Explorer can already do.
 */
public enum AskAction implements AskCapability {

    LET_PHONE_IN("let_phone_in",
        "Let a phone that is waiting to join into the fleet.",
        new ToolParameter("code", "The join code the phone is showing, as waiting_to_join gives it.")),

    REFUSE_PHONE("refuse_phone",
        "Refuse a phone that is waiting to join, so its request disappears.",
        new ToolParameter("code", "The join code the phone is showing, as waiting_to_join gives it.")),

    RUN_BACKUP("run_backup",
        "Back up a machine now, with the backup job it already has.",
        new ToolParameter("machine", "The machine, named exactly as the fleet read names it, or its id.")),

    UPDATE_CONTAINER("update_container",
        "Update a container to the newer image its registry serves - one that container_updates lists.",
        new ToolParameter("machine", "The machine, named exactly as the fleet read names it, or its id."),
        new ToolParameter("container", "The container, named exactly as container_updates names it.")),

    LIFT_BLOCK("lift_block",
        "Lift the block on an address that security lists as kept out.",
        new ToolParameter("address", "The blocked address, exactly as security gives it.")),

    TRUST_ADDRESS("trust_address",
        "Trust an address from now on, so it is never kept out again.",
        new ToolParameter("address", "The address to trust, exactly as security gives it."));

    private static final String ONLY_PROPOSES =
        " This only proposes it to the operator as a card; nothing happens until they click it.";

    private final String toolName;
    private final String description;
    private final List<ToolParameter> parameters;

    AskAction(String toolName, String description, ToolParameter... parameters) {
        this.toolName = toolName;
        this.description = description + ONLY_PROPOSES;
        this.parameters = List.of(parameters);
    }

    @Override
    public String toolName() {
        return toolName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public List<ToolParameter> parameters() {
        return parameters;
    }

    /**
     * What the card says. It is the whole of what the operator reads before clicking, so it names the
     * thing by the name they know — the phone's name, not only its code; the machine's name, not its id.
     */
    public String sentence(Map<String, String> arguments) {
        return switch (this) {
            case LET_PHONE_IN -> "Let " + arguments.get("name") + " in (join code " + arguments.get("code") + ").";
            case REFUSE_PHONE -> "Refuse " + arguments.get("name") + " (join code " + arguments.get("code") + ").";
            case RUN_BACKUP -> "Back up " + arguments.get("machine") + " now.";
            case UPDATE_CONTAINER -> "Update " + arguments.get("container") + " on " + arguments.get("machine")
                + " to its newer image.";
            case LIFT_BLOCK -> "Lift the block on " + arguments.get("address") + ".";
            case TRUST_ADDRESS -> "Trust " + arguments.get("address") + " from now on.";
        };
    }
}
