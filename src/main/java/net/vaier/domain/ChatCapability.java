package net.vaier.domain;

import java.util.List;

/**
 * What the model may call while answering (#360): a read ({@link ChatTool}) or a proposed action
 * ({@link ChatAction}), offered through one shape so the adapter that speaks to the API never learns which
 * is which. The name is stable, the description is the model's only guide, and the parameters are exactly
 * what a call must say.
 */
public interface ChatCapability {

    String toolName();

    String description();

    List<ToolParameter> parameters();
}
