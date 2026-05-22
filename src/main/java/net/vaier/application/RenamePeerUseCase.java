package net.vaier.application;

public interface RenamePeerUseCase {

    /**
     * Renames a VPN peer — sets its display name. Only the operator-facing label changes; the
     * peer's {@code id} is immutable, so no WireGuard config files move and live tunnels and
     * published services are unaffected. A blank {@code newName} clears the stored name, after
     * which the peer falls back to the humanised form of its id.
     *
     * @param peerId  the immutable id of the peer to rename
     * @param newName the new display label
     */
    void renamePeer(String peerId, String newName);
}
