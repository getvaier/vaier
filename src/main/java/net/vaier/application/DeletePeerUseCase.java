package net.vaier.application;

public interface DeletePeerUseCase {
    /** {@code callerIp} is where the request came from, or null when no browser asked. */
    void deletePeer(String peerIdentifier, String callerIp);
}
