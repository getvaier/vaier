package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForProbingTcp;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

@Component
@Slf4j
public class JavaSocketTcpProbeAdapter implements ForProbingTcp {

    @Override
    public ProbeResult probe(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return ProbeResult.CONNECTED;
        } catch (ConnectException e) {
            // "Connection refused" — host responded with TCP RST, so it is alive.
            return ProbeResult.REFUSED;
        } catch (SocketTimeoutException e) {
            return ProbeResult.UNREACHABLE;
        } catch (IOException e) {
            log.debug("TCP probe to {}:{} failed: {}", host, port, e.toString());
            return ProbeResult.UNREACHABLE;
        }
    }
}
