package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import net.vaier.domain.ContainerRun;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForRerunningContainers;
import net.vaier.domain.port.ForRestartingContainers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class DockerContainerAdapter implements ForRestartingContainers, ForRerunningContainers {

    private static final long RUN_TIMEOUT_SECONDS = 120;
    private static final int LOG_TAIL_LINES = 5;

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

    /** docker-proxy permits this start only for the named one-shot renderers; see its haproxy template. */
    @Override
    public ContainerRun rerun(String containerName) {
        log.info("Re-running container '{}'", containerName);
        dockerClient.startContainerCmd(containerName).exec();
        Integer exitCode = dockerClient.waitContainerCmd(containerName)
            .exec(new WaitContainerResultCallback())
            .awaitStatusCode(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ContainerRun run = new ContainerRun(exitCode == null ? -1 : exitCode, "");
        if (run.succeeded()) {
            return run;
        }
        log.warn("Container '{}' exited {}", containerName, run.exitCode());
        return new ContainerRun(run.exitCode(), logTail(containerName));
    }

    private String logTail(String containerName) {
        StringBuilder out = new StringBuilder();
        try {
            dockerClient.logContainerCmd(containerName).withStdOut(true).withStdErr(true).withTail(LOG_TAIL_LINES)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        out.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                })
                .awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Could not read the log of '{}': {}", containerName, e.getMessage());
        }
        return out.toString().strip();
    }
}
