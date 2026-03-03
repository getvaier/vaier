package net.vaier.domain.port;

public interface ForDeletingVpnPeers {
    void deletePeer(String interfaceName, String peerName);
}
