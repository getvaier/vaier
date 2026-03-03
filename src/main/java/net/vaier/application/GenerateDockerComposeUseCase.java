package net.vaier.application;

public interface GenerateDockerComposeUseCase {

    String generateWireguardClientDockerCompose(String peerName, String serverUrl, String serverPort);
}
