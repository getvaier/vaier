package net.vaier.application;

public interface UpdateLanServerDeviceCategoryUseCase {

    /**
     * Sets (or, with a null/blank value, clears) a registered LAN server's device-category
     * override — the operator-pinned icon hint. Clearing reverts the effective category to
     * auto-detection. A non-blank value must be a valid {@link net.vaier.domain.DeviceCategory}
     * name, otherwise {@link IllegalArgumentException} is thrown (surfaced as 400). Throws
     * {@link net.vaier.domain.NotFoundException} if no LAN server has that name.
     */
    void updateDeviceCategory(String name, String deviceCategory);
}
