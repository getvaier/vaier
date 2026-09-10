package net.vaier.domain;

import java.util.List;

/**
 * The <b>Ask tool</b> catalogue (#360): every read of the fleet the model may make while answering, and
 * nothing else. Ask is not a new source of truth — each entry here is a read the Explorer already makes,
 * and none of them carries a secret.
 *
 * <p>The names are stable and the descriptions are the model's only guide to which read answers which
 * question, so both live here rather than being invented again wherever the tools are wired up. A name that
 * drifts between releases turns a working conversation into "I have no tool for that", silently.
 *
 * <p>The whole-fleet reads take nothing, so none of them can be talked into reading something it was not
 * offered. {@link #RUN_ON_MACHINE} is the one tool with arguments — which machine, what to run — and what it
 * may run is {@link ReadOnlyCommand}'s decision, not the model's.
 */
public enum AskTool implements AskCapability {

    FLEET("fleet",
        "Every machine in the fleet, with its name, what kind of machine it is, "
            + "its tunnel address and whether it is connected right now."),

    WAITING_TO_JOIN("waiting_to_join",
        "The phones waiting to be let into the fleet, with the join code each one is "
            + "showing and how many minutes it has left."),

    PUBLISHED_SERVICES("published_services",
        "Every service Vaier publishes, the machine it runs on and whether it is reachable."),

    BACKUPS("backups",
        "The fleet's backup jobs, and how the last run of each one turned out."),

    DISKS("disks",
        "How full each machine's disks are, and which filesystem on it is closest to trouble."),

    CONTAINER_UPDATES("container_updates",
        "The containers running a newer image than the one they were started from."),

    SECURITY("security",
        "Who is being kept out of the fleet's edge right now, and why."),

    RUN_ON_MACHINE("run_on_machine",
        "Run one looking command on a machine over SSH, as Vaier's own login user there and without sudo, "
            + "and return what it printed. Only commands that look are run: " + ReadOnlyCommand.WHAT_IS_ALLOWED
            + ". Anything that could change the machine, and anything under a path where secrets live, is "
            + "refused. Use it for what no other read answers: operating system updates (apt list "
            + "--upgradable, dnf check-update), uptime, logs, processes, a file's contents.",
        new ToolParameter("machine", "The machine, named exactly as the fleet read names it, or its id."),
        new ToolParameter("command", "The command line to run, for example: apt list --upgradable"));

    private final String toolName;
    private final String description;
    private final List<ToolParameter> parameters;

    AskTool(String toolName, String description, ToolParameter... parameters) {
        this.toolName = toolName;
        this.description = description;
        this.parameters = List.of(parameters);
    }

    /** What the model must say when it calls this tool; empty for every whole-fleet read. */
    @Override
    public List<ToolParameter> parameters() {
        return parameters;
    }

    /** The name the model calls this read by. Stable, lower-case snake_case, never a display label. */
    @Override
    public String toolName() {
        return toolName;
    }

    /** One plain sentence saying what this read answers — how the model decides to call it at all. */
    @Override
    public String description() {
        return description;
    }
}
