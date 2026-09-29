package net.vaier.domain;

import java.util.List;
import java.util.Map;

/**
 * The <b>Chat action</b> catalogue (#360 slice 2): what the model may propose. Each is a verb the Explorer
 * already has a button for, and none of them runs on the model's say-so — calling one puts a
 * <b>Confirmation</b> in front of the operator (a <b>Mailed confirmation</b> during an errand), and their yes
 * is what runs it.
 *
 * <p>There is no restart here, deliberately: Vaier has no button to start, stop or restart a container,
 * and an action Chat can propose must be one the Explorer can already do. {@link #CALL_SERVICE} is the one
 * deliberate exception: a write to a published service's own API has no Explorer button, because the
 * service's own UI is its button.
 */
public enum ChatAction implements ChatCapability {

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
        new ToolParameter("address", "The address to trust, exactly as security gives it.")),

    UPGRADE_OS("upgrade_os",
        "Install the pending OS package updates on a machine - apt or dnf, a plain upgrade, never a reboot.",
        new ToolParameter("machine", "The machine, named exactly as the fleet read names it, or its id.")),

    CALL_SERVICE("call_service",
        "Call a published service's own API - POST, PUT, PATCH or DELETE one path on it, or a GET where "
            + "published_services says askBeforeReading - at its backend, with your own service credential there, so only where "
            + "published_services says marvinHasLogin.",
        new ToolParameter("service", "The published service: its name and machine as published_services "
            + "gives them (openhab on Colina 27), or its address."),
        new ToolParameter("method", "GET, POST, PUT, PATCH or DELETE."),
        new ToolParameter("path", "The path inside the service, with any query, for example "
            + "/rest/items/PoolPump. Never a scheme or a host."),
        ToolParameter.optional("body", "What to send: plain text (ON) or JSON. Leave it out when there is "
            + "nothing to send, and always for a GET."),
        new ToolParameter("headline", "What this call does, in everyday words a non-technical person "
            + "understands, in one sentence of at most " + ActionWording.MAX_HEADLINE_CHARS + " characters - for "
            + "example: Turn on the pool pump at Colina 27. Say what it really does; never make it sound gentler "
            + "than it is. The exact call is shown under it."));

    private static final String ONLY_PROPOSES =
        " This only proposes it to the operator; nothing happens until they say yes.";

    private final String toolName;
    private final String description;
    private final List<ToolParameter> parameters;

    ChatAction(String toolName, String description, ToolParameter... parameters) {
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
     * What the card says: a plain headline, and the exact facts under it. It is the whole of what the operator
     * reads before clicking, so it names things by the names they know — the phone's name, the machine's name.
     */
    public ActionWording wording(Map<String, String> arguments) {
        return switch (this) {
            case LET_PHONE_IN -> new ActionWording("Let " + arguments.get("name") + " join your network.",
                "Join code " + arguments.get("code") + ".");
            case REFUSE_PHONE -> new ActionWording("Turn " + arguments.get("name") + " away.",
                "Join code " + arguments.get("code") + ". Its request to join disappears.");
            case RUN_BACKUP -> new ActionWording("Back up " + arguments.get("machine") + " now.",
                "With the backup job it already has.");
            case UPDATE_CONTAINER -> new ActionWording("Update " + arguments.get("container") + " on "
                + arguments.get("machine") + " to its latest version.", "Container " + arguments.get("container")
                + " gets the newer image its registry serves, and is down for a moment while it restarts.");
            case LIFT_BLOCK -> new ActionWording("Let " + arguments.get("address") + " reach your services again.",
                "It is blocked right now. This lifts the block once; it can still be blocked again later.");
            case TRUST_ADDRESS -> new ActionWording("Always let " + arguments.get("address")
                + " in, and never block it.", "It becomes a trusted address.");
            case UPGRADE_OS -> new ActionWording("Install the system updates on " + arguments.get("machine") + ".",
                "The pending OS package updates, installed with apt or dnf. Vaier does not restart it.");
            // The model writes this headline, so the exact call always stands under it.
            case CALL_SERVICE -> ActionWording.written(arguments.get("headline"),
                ServiceCall.proposed(arguments.get("method"), arguments.get("path"), arguments.get("body"))
                    .details(arguments.get("service")));
        };
    }

    /** What the card says once a yes is under way. A service call's is the service's own answer instead. */
    public ActionWording started(Map<String, String> arguments) {
        return switch (this) {
            case LET_PHONE_IN -> new ActionWording(arguments.get("name") + " can join your network now.", null);
            case REFUSE_PHONE -> new ActionWording("Turned " + arguments.get("name") + " away.", null);
            case RUN_BACKUP -> new ActionWording("Backing up " + arguments.get("machine") + " now.",
                "The Backups pane shows how it goes.");
            case UPDATE_CONTAINER -> new ActionWording("Updating " + arguments.get("container") + " on "
                + arguments.get("machine") + ".", "It is down for a moment while it restarts.");
            case LIFT_BLOCK -> new ActionWording(arguments.get("address") + " can reach your services again.", null);
            case TRUST_ADDRESS -> new ActionWording(arguments.get("address") + " is always let in from now on.", null);
            case UPGRADE_OS -> new ActionWording("Installing the system updates on " + arguments.get("machine") + ".",
                "Vaier says how it went when it is done.");
            case CALL_SERVICE -> throw new IllegalStateException("A service call's outcome is the service's answer.");
        };
    }
}
