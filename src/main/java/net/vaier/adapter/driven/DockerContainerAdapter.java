package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import net.vaier.domain.port.ForRestartingContainers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DockerContainerAdapter implements ForRestartingContainers {

    private final DockerClient dockerClient;

    public DockerContainerAdapter() {
        String dockerHost = getDockerHost();
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

    private String getDockerHost() {
        // Check environment variable first
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && !dockerHost.isEmpty()) {
            return dockerHost;
        }

        // Detect platform
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            // Windows uses named pipes
            return "npipe:////./pipe/docker_engine";
        } else if (os.contains("mac")) {
            // macOS can use Unix socket
            return "unix:///var/run/docker.sock";
        } else {
            // Linux uses Unix socket
            return "unix:///var/run/docker.sock";
        }
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
