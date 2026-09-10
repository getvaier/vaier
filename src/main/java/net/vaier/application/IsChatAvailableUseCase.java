package net.vaier.application;

/**
 * Whether <b>Chat</b> is offered at all (#360). Asked by the Explorer so the pane appears only when there is
 * something to ask with — an <b>Anthropic API key</b> is the whole of it.
 */
public interface IsChatAvailableUseCase {

    boolean isAvailable();
}
