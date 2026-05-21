package net.vaier.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;

public record LanServer(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                        String description) {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /** Convenience constructor for a LAN server with no description. */
    public LanServer(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {
        this(name, lanAddress, runsDocker, dockerPort, null);
    }

    /** True when this LAN server is the one named {@code candidate} (exact match). */
    public boolean hasName(String candidate) {
        return name.equals(candidate);
    }

    /**
     * Returns a copy of this LAN server under {@code newName} (trimmed). Address, Docker
     * settings and description carry over unchanged — only the label changes.
     *
     * @throws IllegalArgumentException if {@code newName} is null or blank
     */
    public LanServer renamedTo(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("LAN server name must not be blank");
        }
        return new LanServer(newName.trim(), lanAddress, runsDocker, dockerPort, description);
    }

    /**
     * Returns a copy of this LAN server with its description set. A null or blank value
     * clears the description; otherwise it is trimmed.
     */
    public LanServer withDescription(String newDescription) {
        String normalized = (newDescription == null || newDescription.isBlank())
            ? null : newDescription.trim();
        return new LanServer(name, lanAddress, runsDocker, dockerPort, normalized);
    }

    public static void validate(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (lanAddress == null || lanAddress.isBlank()) {
            throw new IllegalArgumentException("lanAddress must not be blank");
        }
        try {
            byte[] addr = InetAddress.getByName(lanAddress).getAddress();
            if (addr.length != 4) {
                throw new IllegalArgumentException("lanAddress must be a valid IPv4 address (was " + lanAddress + ")");
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("lanAddress must be a valid IPv4 address (was " + lanAddress + ")", e);
        }
        if (runsDocker) {
            if (dockerPort == null) {
                throw new IllegalArgumentException("dockerPort is required when runsDocker is true");
            }
            if (dockerPort < MIN_PORT || dockerPort > MAX_PORT) {
                throw new IllegalArgumentException(
                    "dockerPort must be between " + MIN_PORT + " and " + MAX_PORT + " (was " + dockerPort + ")");
            }
        }
    }
}
