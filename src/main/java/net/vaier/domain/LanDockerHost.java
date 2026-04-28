package net.vaier.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;

public record LanDockerHost(String name, String hostIp, int port) {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    public static void validate(String name, String hostIp, int port) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (hostIp == null || hostIp.isBlank()) {
            throw new IllegalArgumentException("hostIp must not be blank");
        }
        try {
            byte[] addr = InetAddress.getByName(hostIp).getAddress();
            if (addr.length != 4) {
                throw new IllegalArgumentException("hostIp must be a valid IPv4 address (was " + hostIp + ")");
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("hostIp must be a valid IPv4 address (was " + hostIp + ")", e);
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(
                "port must be between " + MIN_PORT + " and " + MAX_PORT + " (was " + port + ")");
        }
    }
}
