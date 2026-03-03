package net.vaier.domain.port;

public interface ForGeneratingDockerComposeFiles {

    String generateWireguardClientDockerCompose(DockerComposeConfig config);

    record DockerComposeConfig(
        String peerName,
        String serverUrl,
        String serverPort
    ) {}
}
