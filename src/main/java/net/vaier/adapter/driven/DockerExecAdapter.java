package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForExecutingInContainer;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;

/**
 * The single home for "run a command inside a Docker container, capture its output". Both
 * {@code VpnService} and {@code WireGuardVpnAdapter} used to carry byte-for-byte copies of this
 * mechanism; they now depend on the {@link ForExecutingInContainer} port instead.
 */
@Component
@Slf4j
public class DockerExecAdapter implements ForExecutingInContainer {

    // A healthy Docker host answers fast; keep the connect timeout short so a dead host fails
    // fast. The response timeout is generous enough to cover a container restart's stop grace.
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(45);

    private static final String MASQUERADE_SIDECAR_SUFFIX = "-masquerade";

    private DockerClient dockerClient;

    @PostConstruct
    public void init() {
        try {
            String dockerHost = Server.localDockerHostUrl();
            log.info("Initializing Docker exec client, host: {}", dockerHost);

            DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .withDockerTlsVerify(false)
                .build();

            DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .maxConnections(100)
                .connectionTimeout(CONNECTION_TIMEOUT)
                .responseTimeout(RESPONSE_TIMEOUT)
                .build();

            dockerClient = DockerClientImpl.getInstance(config, httpClient);
            dockerClient.pingCmd().exec();
            log.info("Docker exec client initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Docker exec client: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to connect to Docker daemon", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        if (dockerClient != null) {
            try {
                dockerClient.close();
            } catch (Exception e) {
                log.error("Error closing Docker exec client", e);
            }
        }
    }

    @Override
    public String execute(String containerName, String... command) {
        try {
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerName)
                .withCmd(command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            dockerClient.execStartCmd(execCreateCmdResponse.getId())
                .exec(new DockerExecCallback(stdout, stderr))
                .awaitCompletion();

            String stderrStr = stderr.toString();
            if (!stderrStr.isEmpty()) {
                log.debug("Command stderr: {}", stderrStr);
            }

            return stdout.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while executing command in container " + containerName, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute command in container " + containerName, e);
        }
    }

    @Override
    public String executeWithInput(String containerName, String input, String... command) {
        String bashCommand = String.format("echo '%s' | %s", input, String.join(" ", command));
        return execute(containerName, "bash", "-c", bashCommand);
    }

    @Override
    public void restartWithMasqueradeSidecar(String containerName) {
        try {
            log.info("Restarting container: {}", containerName);
            dockerClient.restartContainerCmd(containerName)
                .withTimeout(30)
                .exec();
            Thread.sleep(2000);
            log.info("Container {} restarted successfully", containerName);

            String masqueradeContainer = containerName + MASQUERADE_SIDECAR_SUFFIX;
            try {
                log.info("Restarting masquerade sidecar: {}", masqueradeContainer);
                dockerClient.restartContainerCmd(masqueradeContainer)
                    .withTimeout(15)
                    .exec();
                Thread.sleep(3000);
                log.info("Masquerade sidecar restarted successfully");
            } catch (Exception e) {
                log.warn("Could not restart masquerade sidecar {}: {}", masqueradeContainer, e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while restarting container {}", containerName, e);
            throw new RuntimeException("Failed to restart container " + containerName, e);
        } catch (Exception e) {
            log.error("Error restarting container {}: {}", containerName, e.getMessage(), e);
            throw new RuntimeException("Failed to restart container " + containerName, e);
        }
    }

    private static class DockerExecCallback extends ResultCallbackTemplate<DockerExecCallback, Frame> {
        private final ByteArrayOutputStream stdout;
        private final ByteArrayOutputStream stderr;

        DockerExecCallback(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public void onNext(Frame frame) {
            try {
                switch (frame.getStreamType()) {
                    case STDOUT:
                    case RAW:
                        stdout.write(frame.getPayload());
                        break;
                    case STDERR:
                        stderr.write(frame.getPayload());
                        break;
                    default:
                        break;
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to process Docker exec output", e);
            }
        }
    }
}
