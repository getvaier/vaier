package net.vaier.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.OpenTerminalSessionUseCase;
import net.vaier.domain.HostKeyMismatchException;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshAuthException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket bridge for the web terminal (#308): on connect it resolves the machine from the path,
 * opens an SSH shell via {@link OpenTerminalSessionUseCase} (authenticated from the vault), and pipes
 * both directions — inbound binary frames are keystrokes, inbound text frames are JSON control
 * ({@code {"type":"resize","cols":..,"rows":..}}), and remote output is relayed as binary frames.
 *
 * <p>Each distinct failure closes the socket with its own application close code + reason so the UI can
 * explain it: {@code 4404} unknown machine, {@code 4401} no credential, {@code 4402} auth failed,
 * {@code 4403} host-key mismatch, {@code 4408} connect/timeout.
 *
 * <p>The path is non-whitelisted, so the oauth2 forward-auth runs on the upgrade request (cookies ride
 * the upgrade) — Vaier does no in-process auth here.
 */
@Slf4j
@RequiredArgsConstructor
public class TerminalWebSocketHandler extends AbstractWebSocketHandler {

    /** Application-range WebSocket close codes for the distinct terminal failure modes. */
    static final int CLOSE_NO_CREDENTIAL = 4401;
    static final int CLOSE_AUTH_FAILED = 4402;
    static final int CLOSE_HOST_KEY_MISMATCH = 4403;
    static final int CLOSE_NOT_FOUND = 4404;
    static final int CLOSE_CONNECT_FAILED = 4408;
    static final int CLOSE_INTERNAL = 4500;

    private static final String SSH_SESSION_ATTR = "sshSession";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OpenTerminalSessionUseCase openTerminalSessionUseCase;

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) {
        String machine = machineFromPath(wsSession.getUri());
        if (machine == null || machine.isBlank()) {
            closeWith(wsSession, CLOSE_NOT_FOUND, "Unknown machine");
            return;
        }
        try {
            SshSession ssh = openTerminalSessionUseCase.openTerminal(machine, new WsOutputListener(wsSession));
            wsSession.getAttributes().put(SSH_SESSION_ATTR, ssh);
        } catch (NotFoundException e) {
            closeWith(wsSession, CLOSE_NOT_FOUND, "No machine named \"" + machine + "\"");
        } catch (NoHostCredentialException e) {
            closeWith(wsSession, CLOSE_NO_CREDENTIAL, "No SSH credential stored for this machine");
        } catch (HostKeyMismatchException e) {
            closeWith(wsSession, CLOSE_HOST_KEY_MISMATCH,
                "Host key changed — refused. Clear the pinned key if the host was rebuilt.");
        } catch (SshAuthException e) {
            closeWith(wsSession, CLOSE_AUTH_FAILED, "Authentication failed — check the stored credential");
        } catch (SshConnectException e) {
            closeWith(wsSession, CLOSE_CONNECT_FAILED, "Could not reach the host");
        } catch (RuntimeException e) {
            log.warn("Terminal open failed for {}", machine, e);
            closeWith(wsSession, CLOSE_INTERNAL, "Terminal failed to open");
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession wsSession, BinaryMessage message) {
        SshSession ssh = ssh(wsSession);
        if (ssh == null) return;
        byte[] data = new byte[message.getPayload().remaining()];
        message.getPayload().get(data);
        ssh.write(data);
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
        SshSession ssh = ssh(wsSession);
        if (ssh == null) return;
        try {
            JsonNode node = OBJECT_MAPPER.readTree(message.getPayload());
            if ("resize".equals(node.path("type").asText())) {
                int cols = node.path("cols").asInt(0);
                int rows = node.path("rows").asInt(0);
                if (cols > 0 && rows > 0) {
                    ssh.resize(cols, rows);
                }
            }
        } catch (IOException e) {
            log.debug("Ignoring malformed terminal control frame: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        SshSession ssh = ssh(wsSession);
        if (ssh != null) {
            ssh.close();
        }
    }

    private static SshSession ssh(WebSocketSession wsSession) {
        return (SshSession) wsSession.getAttributes().get(SSH_SESSION_ATTR);
    }

    /**
     * The machine name from a {@code /machines/{machine}/terminal} URI, URL-decoded (the name can
     * contain spaces, e.g. "Vaier server"). Null when the path doesn't match.
     */
    static String machineFromPath(URI uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        String prefix = "/machines/";
        String suffix = "/terminal";
        if (path == null || !path.startsWith(prefix) || !path.endsWith(suffix)) return null;
        String segment = path.substring(prefix.length(), path.length() - suffix.length());
        if (segment.isEmpty() || segment.contains("/")) return null;
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }

    private static void closeWith(WebSocketSession wsSession, int code, String reason) {
        try {
            wsSession.close(new CloseStatus(code, reason));
        } catch (IOException e) {
            log.debug("Failed to close terminal socket: {}", e.getMessage());
        }
    }

    /** Relays remote shell output to the browser as binary frames; ends the socket when the shell closes. */
    private static final class WsOutputListener implements net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener {
        private final WebSocketSession wsSession;
        private final Object sendLock = new Object();

        WsOutputListener(WebSocketSession wsSession) {
            this.wsSession = wsSession;
        }

        @Override
        public void onOutput(byte[] data) {
            synchronized (sendLock) {
                if (!wsSession.isOpen()) return;
                try {
                    wsSession.sendMessage(new BinaryMessage(data));
                } catch (IOException e) {
                    log.debug("Failed to relay shell output: {}", e.getMessage());
                }
            }
        }

        @Override
        public void onClosed() {
            try {
                if (wsSession.isOpen()) {
                    wsSession.close(CloseStatus.NORMAL.withReason("Shell closed"));
                }
            } catch (IOException e) {
                log.debug("Failed to close terminal socket on shell end: {}", e.getMessage());
            }
        }
    }
}
