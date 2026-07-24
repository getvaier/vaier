package net.vaier.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Optional;

/**
 * A non-WireGuard machine Vaier knows about, persisted in {@code lan-servers.yml}.
 *
 * <p>Identified by its {@link MachineId} — not by its {@link #name()}, which is a freely editable
 * label. Every {@code with*} copy and {@link #renamedTo} carries the id through unchanged: editing
 * what a machine is called must never make it a different machine.
 */
public record LanServer(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                        String description, DeviceCategory deviceCategory, Boolean sshAccessOverride,
                        MachineId machineId) {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    public LanServer {
        if (machineId == null) {
            throw new IllegalArgumentException("LAN server machineId must not be null");
        }
    }

    /**
     * Convenience constructor for a LAN server being <em>created</em> — it mints a fresh
     * {@link MachineId}. A LAN server being <em>read</em> from storage must carry the id it was stored
     * with, so the file adapter uses the full constructor instead. Same for the two overloads below.
     */
    public LanServer(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {
        this(name, lanAddress, runsDocker, dockerPort, null, null, null, MachineId.generate());
    }

    /** Convenience constructor for a new LAN server with a description. Mints a fresh {@link MachineId}. */
    public LanServer(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                     String description) {
        this(name, lanAddress, runsDocker, dockerPort, description, null, null, MachineId.generate());
    }

    /** Convenience constructor for a new LAN server with a device category. Mints a fresh {@link MachineId}. */
    public LanServer(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                     String description, DeviceCategory deviceCategory) {
        this(name, lanAddress, runsDocker, dockerPort, description, deviceCategory, null,
            MachineId.generate());
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
        if (hasControlCharacters(newName)) {
            throw new IllegalArgumentException("LAN server name must not contain control characters");
        }
        if (newName.indexOf('/') >= 0) {
            throw new IllegalArgumentException("LAN server name must not contain '/'");
        }
        return new LanServer(newName.trim(), lanAddress, runsDocker, dockerPort, description,
            deviceCategory, sshAccessOverride, machineId);
    }

    /**
     * Returns a copy of this LAN server with its description set. A null or blank value
     * clears the description; otherwise it is trimmed.
     */
    public LanServer withDescription(String newDescription) {
        String normalized = (newDescription == null || newDescription.isBlank())
            ? null : newDescription.trim();
        return new LanServer(name, lanAddress, runsDocker, dockerPort, normalized, deviceCategory,
            sshAccessOverride, machineId);
    }

    /**
     * Returns a copy of this LAN server with its device-category override set. A null value clears
     * the override, reverting the effective category to auto-detection. Everything else carries over.
     */
    public LanServer withDeviceCategory(DeviceCategory newDeviceCategory) {
        return new LanServer(name, lanAddress, runsDocker, dockerPort, description, newDeviceCategory,
            sshAccessOverride, machineId);
    }

    /**
     * Returns a copy of this LAN server with its SSH-access override set. A null value clears the
     * override, reverting the effective SSH access to the smart default. Everything else carries over.
     */
    public LanServer withSshAccessOverride(Boolean newSshAccessOverride) {
        return new LanServer(name, lanAddress, runsDocker, dockerPort, description, deviceCategory,
            newSshAccessOverride, machineId);
    }

    /**
     * Whether Vaier offers SSH for this LAN server: the {@link #sshAccessOverride() override} when
     * set, else the smart default seeded from the effective device category (a LAN server is always a
     * {@link MachineType#LAN_SERVER}). Never null.
     */
    public boolean effectiveSshAccess() {
        return sshAccessOverride != null
            ? sshAccessOverride
            : Machine.defaultSshAccess(effectiveDeviceCategory(), MachineType.LAN_SERVER);
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
        if (hasControlCharacters(name)) {
            throw new IllegalArgumentException("name must not contain control characters");
        }
        if (name.indexOf('/') >= 0) {
            // The name is used as a /lan-servers/{name} REST path segment; a '/' would make the
            // server impossible to address (Spring rejects encoded slashes).
            throw new IllegalArgumentException("name must not contain '/'");
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

    /**
     * True when {@code value} contains any ISO control character (e.g. CR/LF). Such characters are
     * never legitimate in a machine name and, if persisted, would let an operator-controlled name
     * forge multiline log entries — so names carrying them are rejected at the boundary.
     */
    private static boolean hasControlCharacters(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
