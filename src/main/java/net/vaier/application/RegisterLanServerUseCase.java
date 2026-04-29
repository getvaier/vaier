package net.vaier.application;

public interface RegisterLanServerUseCase {

    /**
     * Register a server on a relay peer's LAN. {@code lanAddress} must fall inside some
     * relay peer's {@code lanCidr}, otherwise an {@link IllegalArgumentException} is thrown.
     * When {@code runsDocker} is true, {@code dockerPort} is required. Re-registering an
     * existing name replaces the prior entry.
     */
    void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort);
}
