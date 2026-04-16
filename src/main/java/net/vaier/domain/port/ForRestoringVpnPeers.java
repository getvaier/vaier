package net.vaier.domain.port;

public interface ForRestoringVpnPeers {
    void restorePeer(ForGettingPeerConfigurations.PeerConfiguration config);
}
