package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForDetectingPihole;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class PiholeDetectionAdapter implements ForDetectingPihole {

    private DockerClient dockerClient;

    @PostConstruct
    public void init() {
        try {
            var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost("unix:///var/run/docker.sock")
                    .withDockerTlsVerify(false)
                    .build();
            var httpClient = new ZerodepDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .maxConnections(10)
                    .connectionTimeout(Duration.ofSeconds(5))
                    .responseTimeout(Duration.ofSeconds(10))
                    .build();
            dockerClient = DockerClientImpl.getInstance(config, httpClient);
        } catch (Exception e) {
            log.warn("Failed to initialize Docker client for Pi-hole detection: {}", e.getMessage());
        }
    }

    PiholeDetectionAdapter(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public Optional<String> detectPiholeIp() {
        if (dockerClient == null) return Optional.empty();
        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .exec();
            return containers.stream()
                    .filter(c -> c.getImage() != null && c.getImage().contains("pihole/pihole"))
                    .findFirst()
                    .flatMap(c -> {
                        var networks = c.getNetworkSettings().getNetworks();
                        if (networks == null) return Optional.empty();
                        return networks.values().stream()
                                .map(n -> n.getIpAddress())
                                .filter(ip -> ip != null && !ip.isEmpty())
                                .findFirst();
                    });
        } catch (Exception e) {
            log.warn("Failed to detect Pi-hole container: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
