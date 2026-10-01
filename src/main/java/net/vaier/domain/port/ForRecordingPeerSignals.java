package net.vaier.domain.port;

/** What a Vaier app tells Vaier through its tunnel about its own connection; stamped now. */
public interface ForRecordingPeerSignals {
    /** The app just connected: the next read of the tunnel must be fresh. */
    void recordHello(String publicKey);

    /** The app is about to disconnect; every later read of that peer carries it. */
    void recordGoodbye(String publicKey);
}
