package net.vaier.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class Cidr {

    private final byte[] network;
    private final int prefix;

    private Cidr(byte[] network, int prefix) {
        this.network = network;
        this.prefix = prefix;
    }

    public static Cidr parse(String cidr) {
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid CIDR: " + cidr);
        }
        byte[] network;
        int prefix;
        try {
            network = InetAddress.getByName(parts[0]).getAddress();
            prefix = Integer.parseInt(parts[1]);
        } catch (UnknownHostException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid CIDR: " + cidr, e);
        }
        if (prefix < 0 || prefix > network.length * 8) {
            throw new IllegalArgumentException("Invalid CIDR prefix: " + cidr);
        }
        return new Cidr(network, prefix);
    }

    public boolean contains(String ip) {
        byte[] target;
        try {
            target = InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return false;
        }
        if (target.length != network.length) {
            return false;
        }
        int fullBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != target[i]) return false;
        }
        if (remainingBits == 0) return true;
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (network[fullBytes] & mask) == (target[fullBytes] & mask);
    }
}
