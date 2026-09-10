package net.vaier.domain;

import java.util.List;

/**
 * What the model may call while answering (#360): a read ({@link AskTool}) or a proposed action
 * ({@link AskAction}), offered through one shape so the adapter that speaks to the API never learns which
 * is which. The name is stable, the description is the model's only guide, and the parameters are exactly
 * what a call must say.
 */
public interface AskCapability {

    String toolName();

    String description();

    List<ToolParameter> parameters();
}
