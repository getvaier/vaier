package net.vaier.domain.port;

/**
 * The VPN server's own public key, as the running interface reports it. Every peer config is rendered
 * against it, and the peer list checks each peer's on-disk config against it on every refresh.
 */
public interface ForGettingServerPublicKey {

    String getServerPublicKey();
}
