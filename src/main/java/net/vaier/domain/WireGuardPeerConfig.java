package net.vaier.domain;

public final class WireGuardPeerConfig {

    private WireGuardPeerConfig() {}

    public static String generate(String privateKey, String ipAddress, String serverPublicKey,
                                  String presharedKey, String serverEndpoint,
                                  MachineType peerType, String lanCidr, String lanAddress, String vpnSubnet,
                                  String description, String name) {
        return generate(privateKey, ipAddress, serverPublicKey, presharedKey, serverEndpoint,
            peerType, lanCidr, lanAddress, vpnSubnet, description, name, null);
    }

    public static String generate(String privateKey, String ipAddress, String serverPublicKey,
                                  String presharedKey, String serverEndpoint,
                                  MachineType peerType, String lanCidr, String lanAddress, String vpnSubnet,
                                  String description, String name, String serverLanCidr) {
        return generate(privateKey, ipAddress, serverPublicKey, presharedKey, serverEndpoint,
            peerType, lanCidr, lanAddress, vpnSubnet, description, name, serverLanCidr, null);
    }

    public static String generate(String privateKey, String ipAddress, String serverPublicKey,
                                  String presharedKey, String serverEndpoint,
                                  MachineType peerType, String lanCidr, String lanAddress, String vpnSubnet,
                                  String description, String name, String serverLanCidr,
                                  String deviceCategory) {
        // lanCidr is intentionally NOT appended to the client-side AllowedIPs: doing so makes
        // wg-quick install a route for that CIDR via wg0 on the relay peer, which hijacks the
        // relay's own LAN. lanCidr is still recorded in the # VAIER metadata below so that
        // VpnService.addPeerToServer adds it to the server-side wg0.conf [Peer] entry, and the
        // install script (#170) installs ip_forward + iptables MASQUERADE/FORWARD on the relay.
        //
        // serverLanCidr — the subnet the Vaier server itself sits on — IS appended for server-type
        // peers (#204): unlike the relay's own lanCidr, this is the server's subnet, so installing
        // a route for it via wg0 lets the server peer reach back into the server's LAN without
        // hijacking the peer's own local connectivity. Mobile/Windows clients already cover this
        // via their default 0.0.0.0/0 AllowedIPs, so the value is only applied when the peer is a
        // server type.
        String allowedIps = peerType.defaultAllowedIps(vpnSubnet);
        if (peerType.isServerType() && serverLanCidr != null && !serverLanCidr.isBlank()) {
            allowedIps = allowedIps + "," + serverLanCidr.trim();
        }

        String vaierJson = vaierJson(peerType, lanCidr, lanAddress, description, name, deviceCategory);

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

    /**
     * Re-renders a peer's installable config from the <em>current</em> generation logic while
     * preserving the secrets and tunnel IP baked into {@code existingContent} — its
     * {@code PrivateKey}, {@code PresharedKey} and {@code Address}. The identity fields
     * (peerType/lanCidr/lanAddress/description/name) are supplied by the caller from the peer's
     * parsed metadata, and the server fields (public key, endpoint, VPN subnet, server LAN CIDR)
     * are the live ones — so a peer whose config predates a generation change (e.g. the server
     * LAN CIDR now appended to a server peer's client-side AllowedIPs, #204/#247) comes back
     * current without rotating its keypair. See {@code Reissue} in UBIQUITOUS_LANGUAGE.md.
     */
    public static String reissue(String existingContent, MachineType peerType, String lanCidr,
                                 String lanAddress, String description, String name,
                                 String serverPublicKey, String serverEndpoint, String vpnSubnet,
                                 String serverLanCidr) {
        return reissue(existingContent, peerType, lanCidr, lanAddress, description, name,
                serverPublicKey, serverEndpoint, vpnSubnet, serverLanCidr, null);
    }

    public static String reissue(String existingContent, MachineType peerType, String lanCidr,
                                 String lanAddress, String description, String name,
                                 String serverPublicKey, String serverEndpoint, String vpnSubnet,
                                 String serverLanCidr, String deviceCategory) {
        return generate(
                readDirective(existingContent, "PrivateKey"),
                readIpAddress(existingContent),
                serverPublicKey,
                readDirective(existingContent, "PresharedKey"),
                serverEndpoint,
                peerType, lanCidr, lanAddress, vpnSubnet, description, name, serverLanCidr,
                deviceCategory);
    }

    /**
     * Whether {@code existingContent} differs from the config current logic would render for the
     * same peer (its {@code Rendered config}). True means the on-disk config is {@code out of date}
     * and a {@code Reissue} would change it. Same arguments as {@link #reissue}.
     */
    public static boolean isOutOfDate(String existingContent, MachineType peerType, String lanCidr,
                                      String lanAddress, String description, String name,
                                      String serverPublicKey, String serverEndpoint, String vpnSubnet,
                                      String serverLanCidr) {
        // The "# VAIER:" comment is pure Vaier-side metadata — never installed into the WireGuard
        // tunnel — and is written by Jackson (different field order, may carry a deviceCategory key
        // that generate() omits). Out-of-date must reflect divergence in the real tunnel directives
        // only, so strip that comment line from both sides before comparing.
        return !stripVaierMetadata(existingContent).equals(stripVaierMetadata(
                reissue(existingContent, peerType, lanCidr, lanAddress, description, name,
                        serverPublicKey, serverEndpoint, vpnSubnet, serverLanCidr)));
    }

    /** Removes the single {@code # VAIER:} metadata comment line from a config string. */
    private static String stripVaierMetadata(String content) {
        if (content == null) return "";
        return content.replaceAll("(?m)^# VAIER:.*$\n?", "");
    }

    public static String vaierJson(MachineType peerType, String lanCidr, String lanAddress,
                                   String description, String name) {
        return vaierJson(peerType, lanCidr, lanAddress, description, name, null);
    }

    public static String vaierJson(MachineType peerType, String lanCidr, String lanAddress,
                                   String description, String name, String deviceCategory) {
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
        // deviceCategory is the operator's icon-only override (any peer type). Kept last in the
        // hand-built JSON and only emitted when an override is pinned, so a non-overridden peer's
        // metadata stays free of the key (preserving auto-detect). It is a fixed enum name, but
        // escaped for symmetry with the other free-text fields.
        if (deviceCategory != null && !deviceCategory.isBlank()) {
            sb.append(",\"deviceCategory\":\"").append(escapeJson(deviceCategory)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Reads a single {@code Key = value} directive back out of a WireGuard {@code .conf} file —
     * the inverse of {@link #generate}. The key must be followed by {@code =} (optional spaces
     * around it); returns {@code ""} when the directive is absent.
     */
    public static String readDirective(String content, String key) {
        if (content == null || key == null) return "";
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + " =") || trimmed.startsWith(key + "=")) {
                return trimmed.substring(trimmed.indexOf('=') + 1).trim();
            }
        }
        return "";
    }

    /**
     * Reads the interface {@code Address} directive and strips its {@code /prefix} mask,
     * yielding the peer's bare VPN IP. Returns {@code ""} when no {@code Address} line is present.
     */
    public static String readIpAddress(String content) {
        String address = readDirective(content, "Address");
        return address.isEmpty() ? "" : address.split("/")[0];
    }

    /**
     * The server-side WireGuard {@code AllowedIPs} value for a peer: its {@code /32} tunnel IP,
     * plus the relay {@code lanCidr} when one is set. Comma-joined with no spaces — {@code wg set
     * ... allowed-ips} requires a single argv token and {@code wg-quick save} preserves it.
     */
    public static String serverAllowedIps(String ipAddress, String lanCidr) {
        String allowedIps = ipAddress + "/32";
        if (lanCidr != null && !lanCidr.isBlank()) {
            return allowedIps + "," + lanCidr.trim();
        }
        return allowedIps;
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
