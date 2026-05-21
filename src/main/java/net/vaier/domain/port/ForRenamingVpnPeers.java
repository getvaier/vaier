package net.vaier.domain.port;

public interface ForRenamingVpnPeers {

    /**
     * Renames a peer's on-disk WireGuard config — moves the {@code peers/<name>/} directory and
     * the {@code <name>.conf} inside it. The running tunnel is unaffected: the server keys peers
     * by public key in {@code wg0.conf}, and routes/launchpad resolve peer names from IP at runtime.
     */
    void renamePeer(String currentName, String newName);
}
