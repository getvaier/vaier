package net.vaier.domain;

import java.util.Collection;

/**
 * The WireGuard VPN subnet (e.g. {@code 10.13.13.0/24}) and the rule for allocating tunnel IPs
 * within it. The {@code .1} address is reserved for the Vaier server itself.
 */
public record VpnSubnet(String cidr) {

    /**
     * The next free tunnel IP: one past the highest last-octet among {@code assignedIps}, never
     * below {@code .2} ({@code .1} is the Vaier server). Entries that are not dotted-quad IPv4
     * addresses are ignored.
     */
    public String nextAvailableIp(Collection<String> assignedIps) {
        int maxLastOctet = 1;
        for (String ip : assignedIps) {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                continue;
            }
            try {
                maxLastOctet = Math.max(maxLastOctet, Integer.parseInt(parts[3]));
            } catch (NumberFormatException e) {
                // a malformed octet — skip this address
            }
        }
        String networkAddress = cidr.split("/")[0];
        String prefix = networkAddress.substring(0, networkAddress.lastIndexOf('.') + 1);
        return prefix + Math.max(maxLastOctet + 1, 2);
    }
}
