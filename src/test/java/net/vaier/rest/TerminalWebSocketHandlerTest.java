package net.vaier.rest;

import net.vaier.application.EndTerminalSessionUseCase;
import net.vaier.application.OpenTerminalSessionUseCase;
import net.vaier.application.OpenTerminalSessionUseCase.OpenedTerminal;
import net.vaier.application.SendHostPasswordUseCase;
import net.vaier.application.SendHostPasswordUseCase.SendPasswordResult;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.PersistentShell;
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
    @Mock EndTerminalSessionUseCase endTerminalSessionUseCase;
    @Mock SendHostPasswordUseCase sendHostPasswordUseCase;
    @Mock WebSocketSession wsSession;
    @Mock SshSession sshSession;

    private TerminalWebSocketHandler handler() {
        return new TerminalWebSocketHandler(
            openTerminalSessionUseCase, endTerminalSessionUseCase, sendHostPasswordUseCase);
    }

    private Map<String, Object> attrs() {
        Map<String, Object> attrs = new HashMap<>();
        lenient().when(wsSession.getAttributes()).thenReturn(attrs);
        return attrs;
    }

    private OpenedTerminal opened(SshSession session) {
        return new OpenedTerminal(session, PersistentShell.Continuity.NEW);
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
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any(), any())).thenReturn(opened(sshSession));

        handler().afterConnectionEstablished(wsSession);

        assertThat(wsSession.getAttributes().get("sshSession")).isSameAs(sshSession);
        verify(wsSession, never()).close(any());
    }

    @Test
    void onConnect_noCredential_closesWith4401() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal"));
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any(), any()))
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
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any(), any()))
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
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any(), listener.capture())).thenReturn(opened(sshSession));
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

        // The prompt output also pushes a password-prompt state frame, so filter for the result reply.
        ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession, org.mockito.Mockito.atLeastOnce()).sendMessage(reply.capture());
        assertThat(reply.getAllValues()).anySatisfy(m ->
            assertThat(m.getPayload()).contains("password-result").contains("SENT"));
    }

    @Test
    void sendPasswordReply_neverContainsTheSecret() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal"));
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any(), any())).thenReturn(opened(sshSession));
        lenient().when(wsSession.isOpen()).thenReturn(true);
        when(sendHostPasswordUseCase.sendPassword(eq("nas"), eq(sshSession), any()))
            .thenReturn(SendPasswordResult.SENT);

        TerminalWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(wsSession);
        handler.handleMessage(wsSession, new TextMessage("{\"type\":\"send-password\"}"));

        ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession, org.mockito.Mockito.atLeastOnce()).sendMessage(reply.capture());
        // The handler never sees the secret, so it cannot leak — assert no frame carries it.
        assertThat(reply.getAllValues()).allSatisfy(m ->
            assertThat(m.getPayload()).doesNotContain("secret").doesNotContain("s3cret"));
    }

    @Test
    void onConnect_sendsShellModeFrame_reflectingContinuity() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal?pane=abc"));
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), eq("abc"), any()))
            .thenReturn(new OpenedTerminal(sshSession, PersistentShell.Continuity.REATTACHED));

        handler().afterConnectionEstablished(wsSession);

        // The pane id from the query is passed through, and the truthful mode is pushed to the browser.
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession, org.mockito.Mockito.atLeastOnce()).sendMessage(sent.capture());
        assertThat(sent.getAllValues()).anySatisfy(m ->
            assertThat(m.getPayload()).contains("shell-mode").contains("reattached"));
    }

    @Test
    void unknownControlFrame_isIgnored() throws Exception {
        Map<String, Object> attrs = attrs();
        attrs.put("sshSession", sshSession);

        handler().handleMessage(wsSession, new TextMessage("{\"type\":\"run-anything-else\"}"));

        verify(sshSession, never()).resize(org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
        verify(sshSession, never()).write(any());
    }

    @Test
    void paneFromQuery_readsThePaneParameter() {
        assertThat(TerminalWebSocketHandler.paneFromQuery(
            URI.create("wss://host/machines/nas/terminal?pane=p-42"))).isEqualTo("p-42");
        assertThat(TerminalWebSocketHandler.paneFromQuery(
            URI.create("wss://host/machines/nas/terminal"))).isNull();
    }

    @Test
    void tailBuffer_isBoundedToLast512Bytes() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal"));
        ArgumentCaptor<SshOutputListener> listener = ArgumentCaptor.forClass(SshOutputListener.class);
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any(), listener.capture())).thenReturn(opened(sshSession));
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
    void pushesPasswordPromptState_onlyWhenItChanges() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(URI.create("wss://host/machines/nas/terminal"));
        ArgumentCaptor<SshOutputListener> listener = ArgumentCaptor.forClass(SshOutputListener.class);
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any(), listener.capture())).thenReturn(opened(sshSession));
        when(wsSession.isOpen()).thenReturn(true);

        TerminalWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(wsSession);
        SshOutputListener out = listener.getValue();

        // Ordinary output — no prompt, so no state frame is pushed.
        out.onOutput("Last login: Tue\r\n".getBytes(StandardCharsets.UTF_8));
        // A password prompt appears — push showing:true once.
        out.onOutput("[sudo] password for geir: ".getBytes(StandardCharsets.UTF_8));
        // More output that is still a prompt — no duplicate frame.
        out.onOutput("[sudo] password for geir: ".getBytes(StandardCharsets.UTF_8));
        // The prompt is answered and a shell prompt follows — push showing:false once.
        out.onOutput("\r\ngeir@nas:~$ ".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<TextMessage> text = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession, org.mockito.Mockito.atLeastOnce()).sendMessage(text.capture());
        java.util.List<String> promptFrames = text.getAllValues().stream()
            .map(TextMessage::getPayload)
            .filter(p -> p.contains("password-prompt"))
            .toList();
        assertThat(promptFrames).containsExactly(
            "{\"type\":\"password-prompt\",\"showing\":true}",
            "{\"type\":\"password-prompt\",\"showing\":false}");
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
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), any(), listener.capture())).thenReturn(opened(sshSession));
        when(wsSession.isOpen()).thenReturn(true);

        handler().afterConnectionEstablished(wsSession);
        listener.getValue().onOutput("hello".getBytes(StandardCharsets.UTF_8));

        // onConnect also sends the shell-mode text frame, so pick out the single binary output frame.
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage> sent =
            ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(wsSession, org.mockito.Mockito.atLeastOnce()).sendMessage(sent.capture());
        BinaryMessage binary = sent.getAllValues().stream()
            .filter(m -> m instanceof BinaryMessage).map(m -> (BinaryMessage) m)
            .reduce((a, b) -> b).orElseThrow();
        byte[] payload = new byte[binary.getPayload().remaining()];
        binary.getPayload().get(payload);
        assertThat(new String(payload, StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    // --- ending a shell: only an explicit close kills the tmux session ---------------------------

    @Test
    void endShellFrame_endsThePanesPersistentShell_andClosesTheSocket() throws Exception {
        attrs();
        when(wsSession.getUri()).thenReturn(
            URI.create("wss://host/machines/nas/terminal?pane=pane1"));
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), eq("pane1"), any()))
            .thenReturn(opened(sshSession));
        TerminalWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(wsSession);

        handler.handleTextMessage(wsSession, new TextMessage("{\"type\":\"end-shell\"}"));

        // The operator closed the pane: the shell is done, so the session must not outlive it.
        verify(endTerminalSessionUseCase).endTerminal("nas", "pane1");
    }

    @Test
    void droppedSocket_doesNotEndTheShell() {
        attrs();
        when(wsSession.getUri()).thenReturn(
            URI.create("wss://host/machines/nas/terminal?pane=pane1"));
        when(openTerminalSessionUseCase.openTerminal(eq("nas"), eq("pane1"), any()))
            .thenReturn(opened(sshSession));
        TerminalWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(wsSession);

        handler.afterConnectionClosed(wsSession, CloseStatus.NO_CLOSE_FRAME);

        // This is the whole promise of a persistent shell: a dropped tunnel, a closed laptop or a Vaier
        // redeploy must leave the session alive so the reconnect can reattach to it.
        verify(endTerminalSessionUseCase, never()).endTerminal(any(), any());
        verify(sshSession).close();
    }
}
