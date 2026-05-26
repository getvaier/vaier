package net.vaier.domain;

public enum MachineType {
    MOBILE_CLIENT,
    WINDOWS_CLIENT,
    UBUNTU_SERVER,
    WINDOWS_SERVER,
    LAN_SERVER;

    public boolean isServerType() {
        return this == UBUNTU_SERVER || this == WINDOWS_SERVER || this == LAN_SERVER;
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
