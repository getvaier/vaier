package net.vaier.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;

public record LanServer(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

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
