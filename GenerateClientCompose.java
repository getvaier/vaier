public class GenerateClientCompose {

    public static void main(String[] args) {
        String serverUrl = "wireweave.eilertsen.family";
        String serverPort = "51820";
        String peerName = "peer1";
        String timezone = "Europe/Oslo";
        String configPath = "./wireguard-client/config";

        // Parse command line arguments
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 < args.length) {
                String key = args[i].replaceFirst("^--", "");
                String value = args[i + 1];
                switch (key) {
                    case "serverUrl": serverUrl = value; break;
                    case "serverPort": serverPort = value; break;
                    case "peerName": peerName = value; break;
                    case "timezone": timezone = value; break;
                    case "configPath": configPath = value; break;
                }
            }
        }

        String compose = generateClientCompose(timezone, configPath);
        String instructions = generateInstructions(serverUrl, serverPort, peerName, configPath);

        System.out.println("# docker-compose.client.yml");
        System.out.println(compose);
        System.out.println("\n---\n");
        System.out.println(instructions);
    }

    private static String generateClientCompose(String timezone, String configPath) {
        return String.format(
            "services:\n" +
            "  wireguard-client:\n" +
            "    image: lscr.io/linuxserver/wireguard:latest\n" +
            "    container_name: wireguard-client\n" +
            "    cap_add:\n" +
            "      - NET_ADMIN\n" +
            "      - SYS_MODULE\n" +
            "    environment:\n" +
            "      - PUID=1000\n" +
            "      - PGID=1000\n" +
            "      - TZ=%s\n" +
            "    volumes:\n" +
            "      - %s:/config\n" +
            "      - /lib/modules:/lib/modules:ro\n" +
            "    sysctls:\n" +
            "      - net.ipv4.conf.all.src_valid_mark=1\n" +
            "    restart: unless-stopped\n",
            timezone, configPath
        );
    }

    private static String generateInstructions(String serverUrl, String serverPort, String peerName, String configPath) {
        return String.format(
            "# WireGuard Client Setup Instructions\n\n" +
            "## Prerequisites\n" +
            "1. Ensure the WireGuard server is running\n" +
            "2. Generate a peer configuration on the server (PEERS=1 or more)\n\n" +
            "## Setup Steps\n\n" +
            "1. Create the config directory:\n" +
            "   mkdir -p %s\n\n" +
            "2. Copy the peer configuration from the server:\n" +
            "   - On the server, find the peer config at: ./wireguard/config/%s/%s.conf\n" +
            "   - Copy this file to your client at: %s/wg0.conf\n\n" +
            "3. Start the client:\n" +
            "   docker-compose -f docker-compose.client.yml up -d\n\n" +
            "4. Verify connection:\n" +
            "   docker exec wireguard-client wg show\n\n" +
            "## Configuration Notes\n\n" +
            "- Server: %s:%s\n" +
            "- The client will route all traffic through the VPN (0.0.0.0/0)\n" +
            "- Internal subnet: 10.13.13.0/24\n" +
            "- Peer name on server: %s\n\n" +
            "## Troubleshooting\n\n" +
            "- Check logs: docker logs wireguard-client\n" +
            "- Verify server is accessible: ping %s\n" +
            "- Ensure port %s/udp is open on the server",
            configPath, peerName, peerName, configPath, serverUrl, serverPort, peerName, serverUrl, serverPort
        );
    }
}
