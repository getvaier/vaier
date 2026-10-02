package net.vaier.application;

/**
 * Clears the mark on every machine switched off on purpose that Vaier reaches again — the LAN probe answers or
 * the tunnel handshakes. Asked by the watcher after it has judged its own tick, so a comeback is never mailed.
 */
public interface NoticeMachinesBackOnUseCase {

    void noticeMachinesBackOn();
}
