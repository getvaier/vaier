package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GenerateDockerComposeUseCase;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class GenerateDockerComposeService implements GenerateDockerComposeUseCase {

    private final ForGeneratingDockerComposeFiles dockerComposeGenerator;

    @Override
    public String generateWireguardClientDockerCompose(String peerName, String serverUrl, String serverPort) {
        log.info("Generating docker-compose for peer: {}", peerName);
        ForGeneratingDockerComposeFiles.DockerComposeConfig config =
            new ForGeneratingDockerComposeFiles.DockerComposeConfig(peerName, serverUrl, serverPort);
        return dockerComposeGenerator.generateWireguardClientDockerCompose(config);
    }
}
