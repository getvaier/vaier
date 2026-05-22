package net.vaier.domain.port;

public interface ForUpdatingPeerConfigurations {

    void updateLanAddress(String peerId, String lanAddress);

    void updateLanCidr(String peerId, String lanCidr);

    void updateDescription(String peerId, String description);

    /**
     * Sets a peer's display name — the freely editable, operator-facing label. The peer's
     * {@code id} is never affected. A null or blank value clears the stored name, after which
     * the peer falls back to its id's humanised form for display.
     */
    void updateName(String peerId, String name);
}
