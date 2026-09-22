package net.vaier.adapter.driven;

import net.vaier.domain.WireguardClientCompose;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles.DockerComposeConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DockerComposeGeneratorAdapterTest {

    private final DockerComposeGeneratorAdapter adapter = new DockerComposeGeneratorAdapter();

    @Test
    void generateWireguardClientDockerCompose_emitsTheDomainsStandaloneComposeDocument() {
        // The compose service definition itself (image, caps, volumes, restart policy) is
        // WireguardClientCompose.standalone()'s content, pinned by WireguardClientComposeTest.
        DockerComposeConfig config = new DockerComposeConfig("alice", "vpn.example.com", "51820");

        String result = adapter.generateWireguardClientDockerCompose(config);

        assertThat(result).startsWith(WireguardClientCompose.standalone());
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
    void generateWireguardClientDockerCompose_setupInstructions_carryThePeerAndServerThrough() {
        record Row(String peerId, String url, String port, String expectedSubstring) {}
        List<Row> rows = List.of(
            new Row("bob", "vpn.example.com", "51820", "./wireguard/config/bob/bob.conf"),
            new Row("alice", "vpn.example.com", "51820", "# Server: vpn.example.com:51820"),
            new Row("phone-2024", "vpn.example.com", "51820", "./wireguard/config/phone-2024/phone-2024.conf"),
            new Row("alice", "vpn.example.com", "51999", "# Server: vpn.example.com:51999"),
            new Row("alice", "203.0.113.7", "51820", "# Server: 203.0.113.7:51820"),
            // Documents current behaviour: the adapter does no input validation;
            // a null peer name is rendered as the literal string "null" by String.format.
            new Row(null, "vpn.example.com", "51820", "./wireguard/config/null/null.conf")
        );

        for (Row row : rows) {
            String result = adapter.generateWireguardClientDockerCompose(
                new DockerComposeConfig(row.peerId(), row.url(), row.port()));

            assertThat(result).as(row.expectedSubstring()).contains(row.expectedSubstring());
        }
    }
}
