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
}
