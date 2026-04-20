package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RestartContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DockerContainerAdapterTest {

    @Test
    void restartContainer_happyPath_invokesRestartWith30sTimeout() {
        DockerClient dockerClient = mock(DockerClient.class);
        RestartContainerCmd restartCmd = mock(RestartContainerCmd.class);
        when(dockerClient.restartContainerCmd("netdata")).thenReturn(restartCmd);
        when(restartCmd.withTimeout(anyInt())).thenReturn(restartCmd);

        DockerContainerAdapter adapter = new DockerContainerAdapter(dockerClient);
        adapter.restartContainer("netdata");

        verify(dockerClient, times(1)).restartContainerCmd("netdata");
        verify(restartCmd).withTimeout(30);
        verify(restartCmd).exec();
    }

    @Test
    void restartContainer_containerNotFound_wrapsExceptionAsRuntimeException() {
        DockerClient dockerClient = mock(DockerClient.class);
        RestartContainerCmd restartCmd = mock(RestartContainerCmd.class);
        when(dockerClient.restartContainerCmd("missing")).thenReturn(restartCmd);
        when(restartCmd.withTimeout(anyInt())).thenReturn(restartCmd);
        when(restartCmd.exec()).thenThrow(new NotFoundException("No such container: missing"));

        DockerContainerAdapter adapter = new DockerContainerAdapter(dockerClient);

        assertThatThrownBy(() -> adapter.restartContainer("missing"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to restart container: missing")
            .hasCauseInstanceOf(NotFoundException.class);
    }

    @Test
    void restartContainer_dockerDaemonUnavailable_wrapsExceptionAsRuntimeException() {
        DockerClient dockerClient = mock(DockerClient.class);
        RestartContainerCmd restartCmd = mock(RestartContainerCmd.class);
        when(dockerClient.restartContainerCmd("netdata")).thenReturn(restartCmd);
        when(restartCmd.withTimeout(anyInt())).thenReturn(restartCmd);
        when(restartCmd.exec()).thenThrow(new DockerException("Cannot connect to the Docker daemon", 500));

        DockerContainerAdapter adapter = new DockerContainerAdapter(dockerClient);

        assertThatThrownBy(() -> adapter.restartContainer("netdata"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to restart container: netdata")
            .hasCauseInstanceOf(DockerException.class);
    }

    @Test
    void restartContainer_unexpectedException_isAlsoWrapped() {
        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.restartContainerCmd("netdata"))
            .thenThrow(new IllegalStateException("client closed"));

        DockerContainerAdapter adapter = new DockerContainerAdapter(dockerClient);

        assertThatThrownBy(() -> adapter.restartContainer("netdata"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to restart container: netdata")
            .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void restartContainer_containerNameWithSpecialCharacters_isPassedThroughVerbatim() {
        DockerClient dockerClient = mock(DockerClient.class);
        RestartContainerCmd restartCmd = mock(RestartContainerCmd.class);
        when(dockerClient.restartContainerCmd("compose_stack-1")).thenReturn(restartCmd);
        when(restartCmd.withTimeout(anyInt())).thenReturn(restartCmd);

        DockerContainerAdapter adapter = new DockerContainerAdapter(dockerClient);
        adapter.restartContainer("compose_stack-1");

        verify(dockerClient).restartContainerCmd("compose_stack-1");
    }

    @Test
    void restartContainer_returnsVoid_doesNotPropagateRestartCmdResult() {
        DockerClient dockerClient = mock(DockerClient.class);
        RestartContainerCmd restartCmd = mock(RestartContainerCmd.class);
        when(dockerClient.restartContainerCmd("netdata")).thenReturn(restartCmd);
        when(restartCmd.withTimeout(anyInt())).thenReturn(restartCmd);

        DockerContainerAdapter adapter = new DockerContainerAdapter(dockerClient);

        // Returns no value; must not throw on a normal restart.
        adapter.restartContainer("netdata");

        assertThat(adapter).isNotNull();
    }
}
