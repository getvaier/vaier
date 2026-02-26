package com.wireweave.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.wireweave.domain.port.ForRestartingContainers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DockerContainerAdapter implements ForRestartingContainers {

    private final DockerClient dockerClient;

    public DockerContainerAdapter() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost("unix:///var/run/docker.sock")
            .build();
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    @Override
    public void restartContainer(String containerName) {
        try {
            log.info("Restarting container '{}'", containerName);
            dockerClient.restartContainerCmd(containerName).exec();
            log.info("Container '{}' restarted successfully", containerName);
        } catch (Exception e) {
            log.error("Failed to restart container '{}'", containerName, e);
            throw new RuntimeException("Failed to restart container: " + containerName, e);
        }
    }
}
