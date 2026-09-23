package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;

/**
 * Every peer Vaier holds a config for, whether or not the running interface knows it right now. A
 * peer the interface has forgotten — a restart that re-read a config without it, a removal that
 * stopped short — is still a machine with a directory, and it has to be listed to be deletable.
 */
public final class PeerRoster {

    private PeerRoster() {}

    public static List<VpnClient> reconcile(List<VpnClient> live, List<PeerConfiguration> configured) {
        List<VpnClient> roster = new ArrayList<>(live);
        for (PeerConfiguration config : configured) {
            String ip = config.ipAddress();
            if (!hasAddress(config)) continue;
            if (live.stream().anyMatch(client -> client.containsAddress(ip))) continue;
            roster.add(VpnClient.absent(config.publicKey(), ip));
        }
        return roster;
    }

    /** The configs that count, keyed by tunnel address; two claiming one address resolve to the first. */
    public static Map<String, PeerConfiguration> byAddress(List<PeerConfiguration> configured) {
        Map<String, PeerConfiguration> byAddress = new LinkedHashMap<>();
        for (PeerConfiguration config : configured) {
            if (hasAddress(config)) byAddress.putIfAbsent(config.ipAddress(), config);
        }
        return byAddress;
    }

    private static boolean hasAddress(PeerConfiguration config) {
        return config.ipAddress() != null && !config.ipAddress().isBlank();
    }
}
