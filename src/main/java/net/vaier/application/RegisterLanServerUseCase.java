package net.vaier.application;

public interface RegisterLanServerUseCase {

    /**
     * Register a server on a relay peer's LAN. {@code lanAddress} must fall inside some
     * relay peer's {@code lanCidr}, otherwise an {@link IllegalArgumentException} is thrown.
     * When {@code runsDocker} is true, {@code dockerPort} is required. Re-registering an
     * existing name replaces the prior entry.
     */
    void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort);

    /** As above, with an optional free-text {@code description}. */
    void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                  String description);

    /**
     * As above, with an optional device-category override (the icon hint). A null override leaves
     * the effective category to auto-detection; a non-null value is persisted as the override.
     * Lets the Add Machine modal pre-fill the category from a scan pick.
     */
    void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                  String description, net.vaier.domain.DeviceCategory deviceCategory);
}
