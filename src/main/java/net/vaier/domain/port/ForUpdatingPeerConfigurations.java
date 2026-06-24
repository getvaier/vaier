package net.vaier.domain.port;

public interface ForUpdatingPeerConfigurations {

    void updateLanAddress(String peerId, String lanAddress);

    void updateLanCidr(String peerId, String lanCidr);

    void updateDescription(String peerId, String description);

    /**
     * Sets a peer's device-category override — the operator-pinned icon hint, orthogonal to the
     * peer's {@code MachineType} (which drives routing). {@code deviceCategory} is a
     * {@link net.vaier.domain.DeviceCategory} name; a null or blank value clears the override, after
     * which the effective category reverts to auto-detection. The value is validated by the caller.
     */
    void updateDeviceCategory(String peerId, String deviceCategory);

    /**
     * Marks (or, with {@code false}, unmarks) a peer as Vaier's internet gateway in its
     * {@code # VAIER:} metadata (#174). Server-side metadata only — never touches the client tunnel
     * directives. The caller enforces the at-most-one-gateway invariant; this only writes the flag.
     */
    void updateInternetGateway(String peerId, boolean isGateway);

    /**
     * Sets a peer's display name — the freely editable, operator-facing label. The peer's
     * {@code id} is never affected. A null or blank value clears the stored name, after which
     * the peer falls back to its id's humanised form for display.
     */
    void updateName(String peerId, String name);

    /**
     * Replaces the peer's entire {@code .conf} with {@code newContent}. Used by a {@code Reissue}
     * to persist a freshly rendered config; the existing {@code update*} methods only patch a
     * single {@code # VAIER:} field. Throws {@code PeerNotFoundException} if the peer is unknown.
     */
    void rewriteConfig(String peerId, String newContent);
}
