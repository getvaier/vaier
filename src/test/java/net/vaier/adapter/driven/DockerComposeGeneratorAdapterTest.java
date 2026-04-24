package net.vaier.adapter.driven;

import net.vaier.domain.port.ForGeneratingDockerComposeFiles.DockerComposeConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DockerComposeGeneratorAdapterTest {

    private final DockerComposeGeneratorAdapter adapter = new DockerComposeGeneratorAdapter();

    @Test
    void generateWireguardClientDockerCompose_validConfig_includesServiceDefinition() {
        DockerComposeConfig config = new DockerComposeConfig("alice", "vpn.example.com", "51820");

        String result = adapter.generateWireguardClientDockerCompose(config);

        assertThat(result)
            .contains("services:")
            .contains("wireguard-client:")
            .contains("image: lscr.io/linuxserver/wireguard:1.0.20250521-r1-ls110")
            .contains("container_name: wireguard-client")
            .doesNotContain("wireguard:latest");
    }

    @Test
    void generateWireguardClientDockerCompose_pinsWireguardImageToSameVersionAsServer() throws Exception {
        // Drift guard: client image must match the server's docker-compose.yml wireguard pin.
        String serverCompose = Files.readString(Path.of("docker-compose.yml"));
        Matcher m = Pattern.compile("image:\\s*(lscr\\.io/linuxserver/wireguard:\\S+)").matcher(serverCompose);
        assertThat(m.find()).as("server docker-compose.yml should declare a wireguard image").isTrue();
        String serverImage = m.group(1);
        assertThat(serverImage).as("server wireguard must be pinned, not :latest").doesNotEndWith(":latest");

        String clientCompose = adapter.generateWireguardClientDockerCompose(
            new DockerComposeConfig("alice", "vpn.example.com", "51820"));

        assertThat(clientCompose).contains("image: " + serverImage);
    }

    @Test
    void generateWireguardClientDockerCompose_validConfig_includesRequiredCapabilities() {
        DockerComposeConfig config = new DockerComposeConfig("alice", "vpn.example.com", "51820");

        String result = adapter.generateWireguardClientDockerCompose(config);

        assertThat(result)
            .contains("cap_add:")
            .contains("- NET_ADMIN")
            .contains("- SYS_MODULE")
            .contains("net.ipv4.conf.all.src_valid_mark=1");
    }

    @Test
    void generateWireguardClientDockerCompose_includesPeerNameInSetupInstructions() {
        DockerComposeConfig config = new DockerComposeConfig("bob", "vpn.example.com", "51820");

        String result = adapter.generateWireguardClientDockerCompose(config);

        assertThat(result).contains("./wireguard/config/bob/bob.conf");
    }

    @Test
    void generateWireguardClientDockerCompose_includesServerUrlAndPort() {
        DockerComposeConfig config = new DockerComposeConfig("alice", "vpn.example.com", "51820");

        String result = adapter.generateWireguardClientDockerCompose(config);

        assertThat(result).contains("# Server: vpn.example.com:51820");
    }

    @Test
    void generateWireguardClientDockerCompose_peerNameWithHyphenAndDigits_isPreserved() {
        DockerComposeConfig config = new DockerComposeConfig("phone-2024", "vpn.example.com", "51820");

        String result = adapter.generateWireguardClientDockerCompose(config);

        assertThat(result).contains("./wireguard/config/phone-2024/phone-2024.conf");
    }

    @Test
    void generateWireguardClientDockerCompose_nonStandardServerPort_isPreserved() {
        DockerComposeConfig config = new DockerComposeConfig("alice", "vpn.example.com", "51999");

        String result = adapter.generateWireguardClientDockerCompose(config);

        assertThat(result).contains("# Server: vpn.example.com:51999");
    }

    @Test
    void generateWireguardClientDockerCompose_ipServerAddress_isPreserved() {
        DockerComposeConfig config = new DockerComposeConfig("alice", "203.0.113.7", "51820");

        String result = adapter.generateWireguardClientDockerCompose(config);

        assertThat(result).contains("# Server: 203.0.113.7:51820");
    }

    @Test
    void generateWireguardClientDockerCompose_includesVolumeMounts() {
        DockerComposeConfig config = new DockerComposeConfig("alice", "vpn.example.com", "51820");

        String result = adapter.generateWireguardClientDockerCompose(config);

        assertThat(result)
            .contains("./wireguard-client/config:/config")
            .contains("/lib/modules:/lib/modules:ro");
    }

    @Test
    void generateWireguardClientDockerCompose_includesRestartPolicy() {
        DockerComposeConfig config = new DockerComposeConfig("alice", "vpn.example.com", "51820");

        String result = adapter.generateWireguardClientDockerCompose(config);

        assertThat(result).contains("restart: unless-stopped");
    }

    @Test
    void generateWireguardClientDockerCompose_nullPeerName_emitsLiteralNullInPaths() {
        // Documents current behaviour: the adapter does no input validation;
        // a null peer name is rendered as the literal string "null" by String.format.
        DockerComposeConfig config = new DockerComposeConfig(null, "vpn.example.com", "51820");

        String result = adapter.generateWireguardClientDockerCompose(config);

        assertThat(result).contains("./wireguard/config/null/null.conf");
    }
}
