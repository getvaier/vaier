package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.RestartContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import net.vaier.domain.ContainerRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    // --- rerun: a one-shot renderer started again and waited for (#264) ---

    private static DockerClient clientWhoseRunEndsWith(int exitCode, LogContainerCmd logCmd) {
        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.startContainerCmd("dex-init")).thenReturn(mock(StartContainerCmd.class));
        WaitContainerCmd waitCmd = mock(WaitContainerCmd.class);
        when(dockerClient.waitContainerCmd("dex-init")).thenReturn(waitCmd);
        WaitContainerResultCallback ended = mock(WaitContainerResultCallback.class);
        when(ended.awaitStatusCode(anyLong(), any(TimeUnit.class))).thenReturn(exitCode);
        when(waitCmd.exec(any(WaitContainerResultCallback.class))).thenReturn(ended);
        if (logCmd != null) when(dockerClient.logContainerCmd("dex-init")).thenReturn(logCmd);
        return dockerClient;
    }

    @Test
    void rerun_startsTheContainer_andAnswersItsExitCode_withTheTailOfItsLogWhenItFailed() {
        LogContainerCmd logCmd = mock(LogContainerCmd.class, RETURNS_SELF);
        when(logCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            callback.onNext(new Frame(StreamType.STDERR, "dex-init: apk: network unreachable\n".getBytes()));
            callback.onComplete();
            return callback;
        });

        DockerClient failing = clientWhoseRunEndsWith(1, logCmd);
        ContainerRun failed = new DockerContainerAdapter(failing).rerun("dex-init");
        verify(failing.startContainerCmd("dex-init")).exec();
        assertThat(failed).isEqualTo(new ContainerRun(1, "dex-init: apk: network unreachable"));

        DockerClient succeeding = clientWhoseRunEndsWith(0, null);
        assertThat(new DockerContainerAdapter(succeeding).rerun("dex-init")).isEqualTo(new ContainerRun(0, ""));
        verify(succeeding, never()).logContainerCmd(anyString());
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
