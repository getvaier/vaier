package net.vaier.domain.port;

public interface ForUpdatingPeerConfigurations {

    void updateLanAddress(String peerName, String lanAddress);

    void updateLanCidr(String peerName, String lanCidr);
}
