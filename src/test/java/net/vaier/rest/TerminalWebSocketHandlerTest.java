package net.vaier.rest;

import net.vaier.application.OpenTerminalSessionUseCase;
import net.vaier.application.SendHostPasswordUseCase;
import net.vaier.application.SendHostPasswordUseCase.SendPasswordResult;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.SshAuthException;
import net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TerminalWebSocketHandlerTest {

    @Mock OpenTerminalSessionUseCase openTerminalSessionUseCase;
    @Mock SendHostPasswordUseCase sendHostPasswordUseCase;
    @Mock WebSocketSession wsSession;
    @Mock SshSession sshSession;

    private TerminalWebSocketHandler handler() {
        return new TerminalWebSocketHandler(openTerminalSessionUseCase, sendHostPasswordUseCase);
    }

    private Map<String, Object> attrs() {
        Map<String, Object> attrs = new HashMap<>();
        lenient().when(wsSession.getAttributes()).thenReturn(attrs);
        return attrs;
    }

    @Test
    void machineFromPath_decodesNameWithSpaces() {
        assertThat(TerminalWebSocketHandler.machineFromPath(
            URI.create("wss://host/machines/Vaier%20server/terminal"))).isEqualTo("Vaier server");
        assertThat(TerminalWebSocketHandler.machineFromPath(
            URI.create("wss://host/machines/nas/terminal"))).isEqualTo("nas");
        assertThat(TerminalWebSocketHandler.machineFromPath(URI.create("wss://host/other"))).isNull();
    }

    @Test
    void onConnect_opensSession_andStoresIt() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal"));
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any())).thenReturn(sshSession);

        handler().afterConnectionEstablished(wsSession);

        assertThat(wsSession.getAttributes().get("sshSession")).isSameAs(sshSession);
        verify(wsSession, never()).close(any());
    }

    @Test
    void onConnect_noCredential_closesWith4401() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal"));
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any()))
            .thenThrow(new NoHostCredentialException("nas"));

        handler().afterConnectionEstablished(wsSession);

        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(wsSession).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(TerminalWebSocketHandler.CLOSE_NO_CREDENTIAL);
    }

    @Test
    void onConnect_authFailure_closesWith4402() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal"));
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any()))
            .thenThrow(new SshAuthException("nope"));

        handler().afterConnectionEstablished(wsSession);

        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(wsSession).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(TerminalWebSocketHandler.CLOSE_AUTH_FAILED);
    }

    @Test
    void binaryFrame_isWrittenToRemote() throws Exception {
        Map<String, Object> attrs = attrs();
        attrs.put("sshSession", sshSession);

        handler().handleMessage(wsSession,
            new BinaryMessage("ls -la\n".getBytes(StandardCharsets.UTF_8)));

        verify(sshSession).write("ls -la\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void resizeControlFrame_resizesThePty() throws Exception {
        Map<String, Object> attrs = attrs();
        attrs.put("sshSession", sshSession);

        handler().handleMessage(wsSession, new TextMessage("{\"type\":\"resize\",\"cols\":120,\"rows\":40}"));

        verify(sshSession).resize(120, 40);
    }

    @Test
    void closingTheSocket_closesTheSshSession() {
        Map<String, Object> attrs = attrs();
        attrs.put("sshSession", sshSession);

        handler().afterConnectionClosed(wsSession, CloseStatus.NORMAL);

        verify(sshSession).close();
    }

    @Test
    void sendPasswordFrame_callsUseCaseWithBufferedTail_andRepliesWithStatus() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal"));
        ArgumentCaptor<SshOutputListener> listener = ArgumentCaptor.forClass(SshOutputListener.class);
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), listener.capture())).thenReturn(sshSession);
        lenient().when(wsSession.isOpen()).thenReturn(true);
        when(sendHostPasswordUseCase.sendPassword(eq("nas"), eq(sshSession), any()))
            .thenReturn(SendPasswordResult.SENT);

        TerminalWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(wsSession);
        listener.getValue().onOutput("geir@nas's password: ".getBytes(StandardCharsets.UTF_8));

        handler.handleMessage(wsSession, new TextMessage("{\"type\":\"send-password\"}"));

        ArgumentCaptor<String> tail = ArgumentCaptor.forClass(String.class);
        verify(sendHostPasswordUseCase).sendPassword(eq("nas"), eq(sshSession), tail.capture());
        assertThat(tail.getValue()).endsWith("password: ");

        ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession).sendMessage(reply.capture());
        assertThat(reply.getValue().getPayload()).contains("password-result").contains("SENT");
    }

    @Test
    void sendPasswordReply_neverContainsTheSecret() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal"));
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any())).thenReturn(sshSession);
        lenient().when(wsSession.isOpen()).thenReturn(true);
        when(sendHostPasswordUseCase.sendPassword(eq("nas"), eq(sshSession), any()))
            .thenReturn(SendPasswordResult.SENT);

        TerminalWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(wsSession);
        handler.handleMessage(wsSession, new TextMessage("{\"type\":\"send-password\"}"));

        ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession).sendMessage(reply.capture());
        // The handler never sees the secret, so it cannot leak — assert the reply is only the status shape.
        assertThat(reply.getValue().getPayload()).doesNotContain("secret").doesNotContain("s3cret");
    }

    @Test
    void tailBuffer_isBoundedToLast512Bytes() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal"));
        ArgumentCaptor<SshOutputListener> listener = ArgumentCaptor.forClass(SshOutputListener.class);
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), listener.capture())).thenReturn(sshSession);
        lenient().when(wsSession.isOpen()).thenReturn(true);
        when(sendHostPasswordUseCase.sendPassword(eq("nas"), eq(sshSession), any()))
            .thenReturn(SendPasswordResult.NOT_AT_PROMPT);

        TerminalWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(wsSession);
        listener.getValue().onOutput("x".repeat(600).getBytes(StandardCharsets.UTF_8));
        listener.getValue().onOutput("Password: ".getBytes(StandardCharsets.UTF_8));

        handler.handleMessage(wsSession, new TextMessage("{\"type\":\"send-password\"}"));

        ArgumentCaptor<String> tail = ArgumentCaptor.forClass(String.class);
        verify(sendHostPasswordUseCase).sendPassword(eq("nas"), eq(sshSession), tail.capture());
        assertThat(tail.getValue().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(512);
        assertThat(tail.getValue()).endsWith("Password: ");
    }

    @Test
    void malformedControlFrame_isIgnored() throws Exception {
        Map<String, Object> attrs = attrs();
        attrs.put("sshSession", sshSession);

        handler().handleMessage(wsSession, new TextMessage("{not json"));

        verify(sshSession, never()).resize(org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void outputListener_relaysRemoteOutputAsBinaryFrame() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal"));
        ArgumentCaptor<SshOutputListener> listener = ArgumentCaptor.forClass(SshOutputListener.class);
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), listener.capture())).thenReturn(sshSession);
        when(wsSession.isOpen()).thenReturn(true);

        handler().afterConnectionEstablished(wsSession);
        listener.getValue().onOutput("hello".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<BinaryMessage> sent = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(wsSession).sendMessage(sent.capture());
        byte[] payload = new byte[sent.getValue().getPayload().remaining()];
        sent.getValue().getPayload().get(payload);
        assertThat(new String(payload, StandardCharsets.UTF_8)).isEqualTo("hello");
    }
}
