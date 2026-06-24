package net.vaier.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Optional;

public record LanServer(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                        String description, DeviceCategory deviceCategory) {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /** Convenience constructor for a LAN server with no description and no device-category override. */
    public LanServer(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {
        this(name, lanAddress, runsDocker, dockerPort, null, null);
    }

    /** Convenience constructor for a LAN server with a description but no device-category override. */
    public LanServer(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                     String description) {
        this(name, lanAddress, runsDocker, dockerPort, description, null);
    }

    /** True when this LAN server is the one named {@code candidate} (exact match). */
    public boolean hasName(String candidate) {
        return name.equals(candidate);
    }

    /**
     * Find the LAN server in {@code servers} whose {@link #name()} equals {@code name} (exact,
     * case-sensitive). Returns empty when no server matches — callers decide how to react
     * (typically reject the operation that referenced the unknown machine).
     */
    public static Optional<LanServer> findByName(String name, Collection<LanServer> servers) {
        return servers.stream().filter(s -> s.hasName(name)).findFirst();
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
        return new LanServer(newName.trim(), lanAddress, runsDocker, dockerPort, description, deviceCategory);
    }

    /**
     * Returns a copy of this LAN server with its description set. A null or blank value
     * clears the description; otherwise it is trimmed.
     */
    public LanServer withDescription(String newDescription) {
        String normalized = (newDescription == null || newDescription.isBlank())
            ? null : newDescription.trim();
        return new LanServer(name, lanAddress, runsDocker, dockerPort, normalized, deviceCategory);
    }

    /**
     * Returns a copy of this LAN server with its device-category override set. A null value clears
     * the override, reverting the effective category to auto-detection. Everything else carries over.
     */
    public LanServer withDeviceCategory(DeviceCategory newDeviceCategory) {
        return new LanServer(name, lanAddress, runsDocker, dockerPort, description, newDeviceCategory);
    }

    /**
     * The category Vaier shows for this LAN server: the operator's {@link #deviceCategory() override}
     * when one is pinned, otherwise the category auto-detected from the name. A LAN server persists no
     * {@link MachineType} or {@link LanMachineRole}, so detection runs on the name alone (with
     * {@link DeviceCategory#GENERIC} as the fallback). Never null.
     */
    public DeviceCategory effectiveDeviceCategory() {
        return deviceCategory != null ? deviceCategory : DeviceCategory.detect(name, null, null);
    }

    /** True when an explicit device-category override is pinned (rather than auto-detected). */
    public boolean deviceCategoryOverridden() {
        return deviceCategory != null;
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
