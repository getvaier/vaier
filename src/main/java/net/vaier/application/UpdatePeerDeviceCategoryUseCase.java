package net.vaier.application;

public interface UpdatePeerDeviceCategoryUseCase {

    /**
     * Sets (or, with a null/blank value, clears) a VPN peer's device-category override — the
     * operator-pinned icon hint, orthogonal to the peer's routing {@code MachineType}. Clearing
     * reverts the effective category to auto-detection. A non-blank value must be a valid
     * {@link net.vaier.domain.DeviceCategory} name, otherwise {@link IllegalArgumentException} is
     * thrown (surfaced as 400). Throws {@link net.vaier.domain.PeerNotFoundException} if no peer
     * has that id.
     */
    void updatePeerDeviceCategory(String peerId, String deviceCategory);
}
