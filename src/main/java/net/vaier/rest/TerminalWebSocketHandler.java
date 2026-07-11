package net.vaier.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.OpenTerminalSessionUseCase;
import net.vaier.application.OpenTerminalSessionUseCase.OpenedTerminal;
import net.vaier.application.SendHostPasswordUseCase;
import net.vaier.application.SendHostPasswordUseCase.SendPasswordResult;
import net.vaier.domain.HostKeyMismatchException;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PasswordPrompt;
import net.vaier.domain.PersistentShell;
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
 * ({@code resize}, {@code send-password}), and remote output is relayed as binary frames. Outbound text
 * frames are control replies ({@code shell-mode}, {@code password-prompt}, {@code password-result}); none
 * ever carries a secret.
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
    private static final String MACHINE_ATTR = "machine";
    private static final String TAIL_ATTR = "outputTail";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OpenTerminalSessionUseCase openTerminalSessionUseCase;
    private final SendHostPasswordUseCase sendHostPasswordUseCase;

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) {
        String machine = machineFromPath(wsSession.getUri());
        if (machine == null || machine.isBlank()) {
            closeWith(wsSession, CLOSE_NOT_FOUND, "Unknown machine");
            return;
        }
        try {
            OutputTail tail = new OutputTail();
            String paneId = paneFromQuery(wsSession.getUri());
            OpenedTerminal opened = openTerminalSessionUseCase.openTerminal(
                machine, paneId, new WsOutputListener(wsSession, tail));
            wsSession.getAttributes().put(SSH_SESSION_ATTR, opened.session());
            wsSession.getAttributes().put(MACHINE_ATTR, machine);
            wsSession.getAttributes().put(TAIL_ATTR, tail);
            // Tell the browser truthfully how this open resolved, so its reconnect banner can say
            // "reattached" only when the session was genuinely resumed.
            sendShellMode(wsSession, opened.continuity());
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
            String type = node.path("type").asText();
            if ("resize".equals(type)) {
                int cols = node.path("cols").asInt(0);
                int rows = node.path("rows").asInt(0);
                if (cols > 0 && rows > 0) {
                    ssh.resize(cols, rows);
                }
            } else if ("send-password".equals(type)) {
                sendStoredPassword(wsSession, ssh);
            }
        } catch (IOException e) {
            log.debug("Ignoring malformed terminal control frame: {}", e.getMessage());
        }
    }

    /**
     * Ask the application to send the machine's stored password into the PTY — but only if the buffered
     * tail shows a live prompt (the decision is the use case's). We hand it the session (a domain port
     * type) and the tail; the secret never enters this handler. The reply frame carries the status only.
     */
    private void sendStoredPassword(WebSocketSession wsSession, SshSession ssh) {
        String machine = (String) wsSession.getAttributes().get(MACHINE_ATTR);
        OutputTail tail = (OutputTail) wsSession.getAttributes().get(TAIL_ATTR);
        String recentOutput = tail == null ? "" : tail.snapshot();
        SendPasswordResult result = sendHostPasswordUseCase.sendPassword(machine, ssh, recentOutput);
        try {
            wsSession.sendMessage(new TextMessage(
                "{\"type\":\"password-result\",\"status\":\"" + result.name() + "\"}"));
        } catch (IOException e) {
            log.debug("Failed to relay password-result frame: {}", e.getMessage());
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
     * Push the {@code shell-mode} control frame stating how the persistent shell resolved:
     * {@code reattached} (the tmux session was resumed), {@code new} (a fresh session was created), or
     * {@code plain} (tmux absent — a non-persistent shell). The browser uses it to write a truthful
     * reconnect banner instead of always implying continuity.
     */
    private static void sendShellMode(WebSocketSession wsSession, PersistentShell.Continuity continuity) {
        String mode = switch (continuity) {
            case REATTACHED -> "reattached";
            case NEW -> "new";
            case PLAIN -> "plain";
        };
        try {
            wsSession.sendMessage(new TextMessage("{\"type\":\"shell-mode\",\"mode\":\"" + mode + "\"}"));
        } catch (IOException e) {
            log.debug("Failed to send shell-mode frame: {}", e.getMessage());
        }
    }

    /**
     * The stable per-pane id from the {@code ?pane=…} query of the terminal WebSocket URI, used to name
     * the machine's tmux session so a reconnect for the same pane reattaches and two panes never share one
     * session. Null when absent (the domain then falls back to a single default session name).
     */
    static String paneFromQuery(URI uri) {
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String pair : uri.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "pane".equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
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

    /**
     * A strictly bounded rolling window of the most recent PTY output (last {@value #MAX_BYTES} bytes),
     * kept so a {@code send-password} request has the recent output to check for a live prompt against.
     * It never grows past the cap — older bytes are dropped as new ones arrive.
     */
    static final class OutputTail {
        static final int MAX_BYTES = 512;
        private final byte[] buf = new byte[MAX_BYTES];
        private int len = 0;

        synchronized void append(byte[] data) {
            if (data.length >= MAX_BYTES) {
                System.arraycopy(data, data.length - MAX_BYTES, buf, 0, MAX_BYTES);
                len = MAX_BYTES;
                return;
            }
            int keepFromBuf = Math.min(len, MAX_BYTES - data.length);
            System.arraycopy(buf, len - keepFromBuf, buf, 0, keepFromBuf);  // slide the retained tail forward
            System.arraycopy(data, 0, buf, keepFromBuf, data.length);
            len = keepFromBuf + data.length;
        }

        synchronized String snapshot() {
            return new String(buf, 0, len, StandardCharsets.UTF_8);
        }
    }

    /**
     * Relays remote shell output to the browser as binary frames; ends the socket when the shell closes.
     * It also watches the buffered tail after each chunk and, when the {@link PasswordPrompt} decision
     * flips, pushes a {@code {"type":"password-prompt","showing":..}} frame so the browser can reveal or
     * hide the Send-password action exactly while the remote is awaiting a password. The frame is sent on
     * <em>change</em> only, never per chunk, and the tail is what makes a prompt split across two reads
     * still register.
     */
    private static final class WsOutputListener implements net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener {
        private final WebSocketSession wsSession;
        private final OutputTail tail;
        private final Object sendLock = new Object();
        private boolean promptShowing = false;

        WsOutputListener(WebSocketSession wsSession, OutputTail tail) {
            this.wsSession = wsSession;
            this.tail = tail;
        }

        @Override
        public void onOutput(byte[] data) {
            tail.append(data);
            boolean nowShowing = PasswordPrompt.isAwaitingPassword(tail.snapshot());
            synchronized (sendLock) {
                if (!wsSession.isOpen()) return;
                try {
                    wsSession.sendMessage(new BinaryMessage(data));
                    if (nowShowing != promptShowing) {
                        promptShowing = nowShowing;
                        wsSession.sendMessage(new TextMessage(
                            "{\"type\":\"password-prompt\",\"showing\":" + nowShowing + "}"));
                    }
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
