package net.vaier.domain.port;

public interface ForRestoringVpnPeers {
    void restorePeer(String interfaceName, ForGettingPeerConfigurations.PeerConfiguration config);
}
