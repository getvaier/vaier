package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForRestartingContainers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DockerContainerAdapter implements ForRestartingContainers {

    private final DockerClient dockerClient;

    public DockerContainerAdapter() {
        String dockerHost = Server.localDockerHostUrl();
        log.info("Using Docker host: {}", dockerHost);

        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(dockerHost)
            .withDockerTlsVerify(false)
            .build();
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    DockerContainerAdapter(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public void restartContainer(String containerName) {
        try {
            log.info("Restarting container '{}'", containerName);
            dockerClient.restartContainerCmd(containerName).withTimeout(30).exec();
            log.info("Container '{}' restarted successfully", containerName);
        } catch (Exception e) {
            log.error("Failed to restart container '{}'", containerName, e);
            throw new RuntimeException("Failed to restart container: " + containerName, e);
        }
    }
}
