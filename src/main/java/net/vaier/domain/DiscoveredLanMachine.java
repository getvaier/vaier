package net.vaier.domain;

import java.util.Collection;
import java.util.List;

/**
 * A host the LAN scanner (issue #246) found responding on a relay peer's LAN (or the Vaier
 * server's own LAN). It is a <em>candidate</em> the operator can register as a {@link LanServer}
 * with one click — not a persisted entity. The domain owns the read-offs an operator and the UI
 * both rely on: what the host probably is, whether it is already registered, and the stable key
 * used to ignore it.
 *
 * @param ipAddress    the responsive LAN IP
 * @param hostname     a resolved hostname, or {@code null} when none was discoverable
 * @param openPorts    the probed service ports the host answered on
 * @param relayAnchor  the relay peer id whose LAN this host sits on, or
 *                     {@link LanAnchor#VAIER_SERVER_NAME} for the Vaier server's own LAN
 * @param ignored      whether the operator has dismissed this host from the discovered list —
 *                     applied at read time from the ignore-list, so an ignored host stays visible
 *                     (under "ignored") for the operator to reveal and un-dismiss
 */
public record DiscoveredLanMachine(
    String ipAddress,
    String hostname,
    List<Integer> openPorts,
    String relayAnchor,
    boolean ignored
) {
    /**
     * Convenience constructor for a freshly scanned host — not yet flagged against the ignore-list.
     * The scan produces these; {@link #withIgnored(Collection)} later stamps the flag.
     */
    public DiscoveredLanMachine(String ipAddress, String hostname, List<Integer> openPorts, String relayAnchor) {
        this(ipAddress, hostname, openPorts, relayAnchor, false);
    }

    /** The advisory role guessed from {@link #openPorts}. */
    public LanMachineRole guessedRole() {
        return LanMachineRole.fromOpenPorts(openPorts);
    }

    /**
     * The registration profile for <em>adopting</em> this discovered host as a {@link LanServer} —
     * every field a one-call adoption needs, derived here so neither the service nor the controller
     * re-derives any of it. The address is the LAN server's address; the Docker settings come from
     * the open ports ({@link LanMachineRole#dockerPort}); the device category is the same read-off
     * the UI shows ({@link DeviceCategory#detect} over hostname then guessed role); the suggested
     * name is the resolved hostname, falling back to the IP when none was discoverable.
     */
    public AdoptionProfile adoptionProfile() {
        Integer dockerPort = LanMachineRole.dockerPort(openPorts);
        boolean runsDocker = dockerPort != null;
        String suggestedName = (hostname == null || hostname.isBlank()) ? ipAddress : hostname.trim();
        DeviceCategory deviceCategory = DeviceCategory.detect(hostname, null, guessedRole());
        return new AdoptionProfile(suggestedName, ipAddress, runsDocker, dockerPort, deviceCategory);
    }

    /**
     * The domain-derived shape a {@link LanServer} is registered from when a discovered host is
     * adopted. A value object: the decision of what the adopted machine looks like lives here, not
     * in the orchestrating service.
     *
     * @param suggestedName  the name to register under when the operator supplies no override
     * @param lanAddress     the LAN server's address (the discovered host's IP)
     * @param runsDocker     whether an open Docker API port was found
     * @param dockerPort     the open Docker API port, or {@code null} when {@code runsDocker} is false
     * @param deviceCategory the auto-detected device category (the icon hint), pinned on adoption
     */
    public record AdoptionProfile(String suggestedName, String lanAddress, boolean runsDocker,
                                  Integer dockerPort, DeviceCategory deviceCategory) {

        /**
         * The name to register the adopted machine under: the operator's {@code nameOverride} when it
         * is non-blank (trimmed), otherwise the {@link #suggestedName()}. Keeps the "override wins,
         * else suggested" choice in the domain rather than the service.
         */
        public String chosenName(String nameOverride) {
            return (nameOverride == null || nameOverride.isBlank()) ? suggestedName : nameOverride.trim();
        }
    }

    /**
     * True when a registered machine already owns this host's address — drop it from the
     * candidates. The claimed addresses span every registered machine type: LAN servers and
     * VPN peers (relays and Ubuntu servers carry a LAN address), so a host already on the map
     * never resurfaces as a candidate.
     */
    public boolean isAlreadyRegistered(Collection<String> registeredAddresses) {
        return registeredAddresses.contains(ipAddress);
    }

    /**
     * The stable key the operator's ignore-list is keyed on: the relay anchor plus the address.
     * Independent of hostname and open ports so ignoring a host survives the next scan finding it
     * with a different snapshot.
     */
    public String ignoreKey() {
        return relayAnchor + "|" + ipAddress;
    }

    /**
     * True when the operator's ignore-list holds this host's {@link #ignoreKey()} — the parallel of
     * {@link #isAlreadyRegistered(Collection)} for dismissal rather than registration. The decision
     * lives here so neither the service nor the controller has to reason about the ignore-list.
     */
    public boolean isIgnored(Collection<String> ignoredKeys) {
        return ignoredKeys.contains(ignoreKey());
    }

    /**
     * A copy of this host with its {@link #ignored} flag set from the current ignore-list. Applied at
     * snapshot read time (ignore happens between scans, with no rescan), so the flag reflects the
     * operator's latest choice immediately.
     */
    public DiscoveredLanMachine withIgnored(Collection<String> ignoredKeys) {
        return new DiscoveredLanMachine(ipAddress, hostname, openPorts, relayAnchor, isIgnored(ignoredKeys));
    }
}
