package net.vaier.domain;

/**
 * <b>Chat</b> was reached for while no <b>Anthropic API key</b> is stored (#360). A state conflict, not a
 * fault: nothing is broken, the one thing Chat needs simply is not there yet. Mapped to {@code 409} beside
 * {@link ConflictException}, carrying a sentence that names the fix.
 */
public class ChatUnavailableException extends RuntimeException {
    public ChatUnavailableException(String message) {
        super(message);
    }
}
