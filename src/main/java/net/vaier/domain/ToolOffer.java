package net.vaier.domain;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One <b>Ask tool</b> wired to the read that answers it (#360). The domain owns the catalogue and the
 * wording; whoever assembles the offers owns the projection — a small, secret-free rendering of what the
 * read returned.
 *
 * <p>The read is given the model's arguments, by parameter name. A whole-fleet read takes none and ignores
 * the map; {@link AskTool#RUN_ON_MACHINE} and every {@link AskAction} read only what their parameters name.
 * An action's "read" proposes — it never runs the action.
 */
public record ToolOffer(AskCapability tool, Function<Map<String, String>, String> read) {

    public ToolOffer {
        if (tool == null) {
            throw new IllegalArgumentException("A tool offer must name the tool it answers for");
        }
        if (read == null) {
            throw new IllegalArgumentException("A tool offer must carry the read that answers it");
        }
    }

    /** A whole-fleet read: it takes nothing, so it is offered as a supplier. */
    public ToolOffer(AskCapability tool, Supplier<String> read) {
        this(tool, read == null ? null : arguments -> read.get());
    }
}
