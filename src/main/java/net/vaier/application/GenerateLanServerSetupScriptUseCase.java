package net.vaier.application;

import java.util.Optional;

public interface GenerateLanServerSetupScriptUseCase {

    /**
     * Renders the single per-host setup script for a registered LAN server: opens the Docker engine
     * API when the host runs Docker, and installs routes via its relay peer when it is relay-anchored.
     * Empty when the server is unknown or has nothing to set up (no Docker and not relay-anchored).
     * Throws {@code IllegalStateException} when the relay peer has no LAN address to route via.
     */
    Optional<String> generateSetupScript(String lanServerName);
}
