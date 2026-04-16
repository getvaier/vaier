package net.vaier.domain;

public enum PeerType {
    MOBILE_CLIENT,
    WINDOWS_CLIENT,
    UBUNTU_SERVER,
    WINDOWS_SERVER;

    public boolean isServerType() {
        return this == UBUNTU_SERVER || this == WINDOWS_SERVER;
    }

    public String defaultAllowedIps(String vpnSubnet) {
        return isServerType() ? vpnSubnet : "0.0.0.0/0";
    }
}
