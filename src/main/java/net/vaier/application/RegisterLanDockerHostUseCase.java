package net.vaier.application;

public interface RegisterLanDockerHostUseCase {

    /**
     * Register a Docker host that lives on a relay peer's LAN. {@code hostIp} must fall
     * inside some relay peer's {@code lanCidr}, otherwise an {@link IllegalArgumentException}
     * is thrown. Re-registering an existing name replaces the prior entry.
     */
    void register(String name, String hostIp, int port);
}
