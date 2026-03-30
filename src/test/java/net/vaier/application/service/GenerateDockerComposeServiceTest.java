package net.vaier.application.service;

import net.vaier.domain.port.ForGeneratingDockerComposeFiles;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles.DockerComposeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateDockerComposeServiceTest {

    @Mock
    ForGeneratingDockerComposeFiles dockerComposeGenerator;

    @InjectMocks
    GenerateDockerComposeService service;

    @Test
    void generateWireguardClientDockerCompose_passesCorrectConfigToPort() {
        when(dockerComposeGenerator.generateWireguardClientDockerCompose(
            new DockerComposeConfig("alice", "vpn.example.com", "51820")
        )).thenReturn("docker-compose-yaml-content");

        String result = service.generateWireguardClientDockerCompose("alice", "vpn.example.com", "51820");

        assertThat(result).isEqualTo("docker-compose-yaml-content");
    }

    @Test
    void generateWireguardClientDockerCompose_constructsDockerComposeConfigRecord() {
        ArgumentCaptor<DockerComposeConfig> captor = ArgumentCaptor.forClass(DockerComposeConfig.class);
        when(dockerComposeGenerator.generateWireguardClientDockerCompose(captor.capture())).thenReturn("");

        service.generateWireguardClientDockerCompose("bob", "server.net", "51820");

        DockerComposeConfig config = captor.getValue();
        assertThat(config.peerName()).isEqualTo("bob");
        assertThat(config.serverUrl()).isEqualTo("server.net");
        assertThat(config.serverPort()).isEqualTo("51820");
    }
}
