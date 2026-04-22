package net.vaier.domain;

public final class WireGuardPeerConfig {

    private WireGuardPeerConfig() {}

    public static String generate(String privateKey, String ipAddress, String serverPublicKey,
                                  String presharedKey, String serverEndpoint,
                                  PeerType peerType, String lanCidr, String lanAddress, String vpnSubnet) {
        String allowedIps = peerType.defaultAllowedIps(vpnSubnet);
        if (lanCidr != null && !lanCidr.isBlank() && peerType == PeerType.UBUNTU_SERVER) {
            allowedIps = allowedIps + ", " + lanCidr;
        }

        String vaierJson = vaierJson(peerType, lanCidr, lanAddress);

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

    public static String vaierJson(PeerType peerType, String lanCidr, String lanAddress) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"peerType\":\"").append(peerType.name()).append("\"");
        boolean serverType = peerType == PeerType.UBUNTU_SERVER;
        if (serverType && lanCidr != null && !lanCidr.isBlank()) {
            sb.append(",\"lanCidr\":\"").append(lanCidr).append("\"");
        }
        if (serverType && lanAddress != null && !lanAddress.isBlank()) {
            sb.append(",\"lanAddress\":\"").append(lanAddress).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }
}
