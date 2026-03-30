package net.vaier.domain;

public enum PeerType {
    MOBILE_CLIENT,
    WINDOWS_CLIENT,
    UBUNTU_SERVER,
    WINDOWS_SERVER;

    public boolean isServerType() {
        return this == UBUNTU_SERVER || this == WINDOWS_SERVER;
    }

    public String defaultAllowedIps() {
        return isServerType() ? "10.13.13.0/24" : "0.0.0.0/0";
    }
}
