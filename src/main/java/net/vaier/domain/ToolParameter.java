package net.vaier.domain;

/**
 * One argument the model gives a tool, in words that tell it what to put there (#360). A parameter that
 * takes {@code many} values is offered as a list and arrives one value per line; an {@code optional} one may
 * be left out.
 */
public record ToolParameter(String name, String description, boolean many, boolean optional) {

    public ToolParameter(String name, String description) {
        this(name, description, false, false);
    }

    public ToolParameter(String name, String description, boolean many) {
        this(name, description, many, false);
    }

    public static ToolParameter optional(String name, String description) {
        return new ToolParameter(name, description, false, true);
    }
}
