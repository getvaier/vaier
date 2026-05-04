package net.vaier.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

public final class Cidr {

    // Strict IPv4 CIDR boundary check — used at the controller boundary to reject
    // shell-metacharacter payloads before any user input reaches a process executor.
    // No alternation, no shell special chars, no IPv6, no whitespace, no hostnames,
    // no leading zeros. Octet/prefix bounds are checked numerically below.
    private static final Pattern STRICT_IPV4_CIDR = Pattern.compile(
        "^(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})/([0-9]{1,2})$");

    private final byte[] network;
    private final int prefix;

    private Cidr(byte[] network, int prefix) {
        this.network = network;
        this.prefix = prefix;
    }

    /**
     * Strict validator for user-supplied {@code lanCidr} values. Closes #195 — without
     * this, a payload like {@code 1.2.3.0/24; id} flowed through to {@code wg set ... allowed-ips}
     * and got executed via {@code sh -c}.
     *
     * <p>This is intentionally stricter than {@link #parse(String)}: no IPv6, no
     * hostnames, no whitespace, no leading zeros — anything that doesn't look exactly
     * like {@code A.B.C.D/N} is rejected. Use this at the application boundary;
     * use {@link #parse(String)} for trusted internal CIDR strings.
     */
    public static void validateLanCidr(String lanCidr) {
        if (lanCidr == null || lanCidr.isBlank()) {
            throw new IllegalArgumentException("lanCidr must not be blank");
        }
        var matcher = STRICT_IPV4_CIDR.matcher(lanCidr);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("lanCidr is not a valid IPv4 CIDR: " + lanCidr);
        }
        for (int i = 1; i <= 4; i++) {
            int octet = Integer.parseInt(matcher.group(i));
            if (octet > 255) {
                throw new IllegalArgumentException("lanCidr octet out of range (0-255): " + lanCidr);
            }
        }
        int prefix = Integer.parseInt(matcher.group(5));
        if (prefix > 32) {
            throw new IllegalArgumentException("lanCidr prefix out of range (0-32): " + lanCidr);
        }
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
