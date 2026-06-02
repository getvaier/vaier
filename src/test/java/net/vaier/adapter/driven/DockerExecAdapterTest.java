package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DockerExecAdapterTest {

    /**
     * Wires up a mock Docker client that drives the adapter all the way to {@code awaitCompletion()}.
     * {@code exec(callback)} returns the very callback the adapter passed in (as the real client does),
     * so the adapter's {@code .awaitCompletion()} runs against a real {@code ResultCallbackTemplate}.
     * The caller pre-interrupts the thread so that {@code awaitCompletion()} throws
     * {@code InterruptedException} — the only checked exception the exec path can produce.
     */
    private DockerClient mockDockerClientReachingAwait() {
        DockerClient dockerClient = mock(DockerClient.class);

        ExecCreateCmd createCmd = mock(ExecCreateCmd.class);
        ExecCreateCmdResponse createResponse = mock(ExecCreateCmdResponse.class);
        ExecStartCmd startCmd = mock(ExecStartCmd.class);

        when(dockerClient.execCreateCmd(any())).thenReturn(createCmd);
        when(createCmd.withCmd(any(String[].class))).thenReturn(createCmd);
        when(createCmd.withAttachStdout(anyBoolean())).thenReturn(createCmd);
        when(createCmd.withAttachStderr(anyBoolean())).thenReturn(createCmd);
        when(createCmd.exec()).thenReturn(createResponse);
        when(createResponse.getId()).thenReturn("exec-id");

        when(dockerClient.execStartCmd("exec-id")).thenReturn(startCmd);
        // Real client returns the callback it was handed; mirror that so the adapter can await on it.
        when(startCmd.exec(any())).thenAnswer(invocation -> invocation.getArgument(0));

        return dockerClient;
    }

    @Test
    void execute_interruptedDuringAwait_isWrappedInUncheckedException() {
        DockerExecAdapter adapter = new DockerExecAdapter();
        ReflectionTestUtils.setField(adapter, "dockerClient", mockDockerClientReachingAwait());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> adapter.execute("wireguard", "wg", "show"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        } finally {
            // The adapter restores the interrupt flag; clear it so it doesn't leak to other tests.
            Thread.interrupted();
        }
    }

    @Test
    void execute_interruptedDuringAwait_restoresInterruptFlag() {
        DockerExecAdapter adapter = new DockerExecAdapter();
        ReflectionTestUtils.setField(adapter, "dockerClient", mockDockerClientReachingAwait());

        Thread.currentThread().interrupt();
        try {
            adapter.execute("wireguard", "wg", "show");
        } catch (RuntimeException expected) {
            // expected — verified separately
        }

        // The adapter must restore the interrupt flag it consumed when catching InterruptedException.
        assertThat(Thread.interrupted()).isTrue();
    }
}
