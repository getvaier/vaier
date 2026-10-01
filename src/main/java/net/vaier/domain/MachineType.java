package net.vaier.domain;

public enum MachineType {
    MOBILE_CLIENT,
    WINDOWS_CLIENT,
    UBUNTU_SERVER,
    LAN_SERVER;

    public boolean isServerType() {
        return this == UBUNTU_SERVER || this == LAN_SERVER;
    }

    /** A personal device: it joins with a key the Vaier app makes, never one Vaier mints. */
    public boolean joinsThroughVaierApp() {
        return this == MOBILE_CLIENT || this == WINDOWS_CLIENT;
    }

    /** Refuses to mint or re-render a config for a machine that joins through the Vaier app. */
    public void requireVaierMintedConfig(String machineName) {
        if (joinsThroughVaierApp()) {
            throw new ConflictException(machineName + " joins through the Vaier app, so Vaier makes no config "
                + "for it. Install the Vaier app from the launchpad on the device and approve its join code.");
        }
    }

    public boolean isVpnPeer() {
        return this != LAN_SERVER;
    }

    public String defaultAllowedIps(String vpnSubnet) {
        return isServerType() ? vpnSubnet : "0.0.0.0/0";
    }

    /**
     * The default {@link MachineType} for an unspecified peer — historically Ubuntu, since
     * the project started as "set up VPN-relay servers on Ubuntu hosts" and that's still the
     * most-common kind of peer. Centralise the value here so adapters, services, and the REST
     * layer never hardcode their own copy.
     */
    public static MachineType defaultType() {
        return UBUNTU_SERVER;
    }
}
