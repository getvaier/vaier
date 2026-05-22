package net.vaier.domain;

public final class WireGuardPeerConfig {

    private WireGuardPeerConfig() {}

    public static String generate(String privateKey, String ipAddress, String serverPublicKey,
                                  String presharedKey, String serverEndpoint,
                                  MachineType peerType, String lanCidr, String lanAddress, String vpnSubnet,
                                  String description, String name) {
        // lanCidr is intentionally NOT appended to the client-side AllowedIPs: doing so makes
        // wg-quick install a route for that CIDR via wg0 on the relay peer, which hijacks the
        // relay's own LAN. lanCidr is still recorded in the # VAIER metadata below so that
        // VpnService.addPeerToServer adds it to the server-side wg0.conf [Peer] entry, and the
        // install script (#170) installs ip_forward + iptables MASQUERADE/FORWARD on the relay.
        String allowedIps = peerType.defaultAllowedIps(vpnSubnet);

        String vaierJson = vaierJson(peerType, lanCidr, lanAddress, description, name);

        String dnsLine = peerType.isServerType()
                ? ""
                : "DNS = 172.20.0.53\n";

        return String.format("""
                # VAIER: %s
                [Interface]
                PrivateKey = %s
                Address = %s/32
                %s
                [Peer]
                PublicKey = %s
                PresharedKey = %s
                Endpoint = %s
                AllowedIPs = %s
                PersistentKeepalive = 25
                """, vaierJson, privateKey, ipAddress, dnsLine,
                serverPublicKey, presharedKey, serverEndpoint, allowedIps);
    }

    public static String vaierJson(MachineType peerType, String lanCidr, String lanAddress,
                                   String description, String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"peerType\":\"").append(peerType.name()).append("\"");
        // name is the operator's display label for the peer — free text, JSON-escaped, and (like
        // description) recorded for any peer type. The peer's id stays the config directory name.
        if (name != null && !name.isBlank()) {
            sb.append(",\"name\":\"").append(escapeJson(name)).append("\"");
        }
        boolean serverType = peerType == MachineType.UBUNTU_SERVER;
        if (serverType && lanCidr != null && !lanCidr.isBlank()) {
            sb.append(",\"lanCidr\":\"").append(lanCidr).append("\"");
        }
        if (serverType && lanAddress != null && !lanAddress.isBlank()) {
            sb.append(",\"lanAddress\":\"").append(lanAddress).append("\"");
        }
        // description is an operator-supplied label that applies to any peer type, so unlike
        // lanCidr/lanAddress it is not gated on server type. It is free text, hence JSON-escaped.
        if (description != null && !description.isBlank()) {
            sb.append(",\"description\":\"").append(escapeJson(description)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
