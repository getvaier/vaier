package net.vaier.application;

/**
 * Store — or clear — the operator's own <b>Anthropic API key</b> (#360). It is the one thing that makes
 * <b>Chat</b> available, so setting it and clearing it are the same operation: a blank key leaves Vaier with
 * none, and Chat goes back to being unavailable.
 *
 * <p>Write-only by design. The key is never read back to the browser — {@code GET /settings/config} answers
 * only whether one is stored.
 */
public interface UpdateAnthropicApiKeyUseCase {

    /** Stores {@code apiKey}, or clears the stored one when it is blank. */
    void updateAnthropicApiKey(String apiKey);
}
