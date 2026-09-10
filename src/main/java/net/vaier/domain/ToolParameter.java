package net.vaier.domain;

/**
 * One argument the model must give a tool, in words that tell it what to put there (#360). A parameter
 * that takes {@code many} values is offered as a list and arrives one value per line.
 */
public record ToolParameter(String name, String description, boolean many) {

    public ToolParameter(String name, String description) {
        this(name, description, false);
    }
}
