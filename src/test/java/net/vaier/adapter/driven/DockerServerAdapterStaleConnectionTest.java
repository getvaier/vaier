package net.vaier.adapter.driven;

import net.vaier.domain.Server;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Vaier host's engine sits behind a socket proxy that closes an idle keep-alive connection after ten
 * seconds, and the scrape comes round every thirty or more. docker-java's client never checks a pooled
 * connection before reusing it, and its one retry survives exactly one dead socket — so every connection a
 * burst had opened was a scrape lost, on the tick after the burst.
 */
class DockerServerAdapterStaleConnectionTest {

    @Test
    void severalFirstScrapesOfOneServerAtOnce_allAnswer() throws Exception {
        // The Explorer and the scheduler can both be the first to ask about a server; creating its
        // client is not a race anyone should lose.
        try (FlakyEngine engine = FlakyEngine.start()) {
            DockerServerAdapter adapter = new DockerServerAdapter();
            Server server = new Server("127.0.0.1", engine.port(), false);

            for (Future<?> f : burst(adapter, server)) {
                assertThat(f.get()).isEqualTo(List.of());
            }
        }
    }

    @Test
    void aScrapeAfterABurstStillAnswers_whenTheProxyHasClosedEveryPooledConnection() throws Exception {
        try (FlakyEngine engine = FlakyEngine.start()) {
            DockerServerAdapter adapter = new DockerServerAdapter();
            Server server = new Server("127.0.0.1", engine.port(), false);
            adapter.getServicesWithExposedPorts(server);

            // A burst — the Explorer loading, say — has several scrapes in flight at once...
            for (Future<?> f : burst(adapter, server)) {
                f.get();
            }
            // ...the proxy closes every idle connection long before the next tick...
            Thread.sleep(FlakyEngine.IDLE_CLOSE_MS * 3);

            // ...and the next scrape must still answer.
            assertThat(adapter.getServicesWithExposedPorts(server)).isEmpty();
        }
    }

    private static List<Future<?>> burst(DockerServerAdapter adapter, Server server) {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> inFlight = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            inFlight.add(pool.submit(() -> adapter.getServicesWithExposedPorts(server)));
        }
        pool.shutdown();
        return inFlight;
    }

    /**
     * Just enough of a Docker engine behind a proxy: answers {@code /_ping} and any listing, answers
     * slowly so a burst really overlaps, and closes a connection the moment it sits idle.
     */
    private static final class FlakyEngine implements AutoCloseable {

        static final int IDLE_CLOSE_MS = 150;
        private static final int SLOW_MS = 100;

        private final ServerSocket socket;
        private final Thread acceptor;
        private volatile boolean open = true;

        private FlakyEngine(ServerSocket socket) {
            this.socket = socket;
            this.acceptor = new Thread(this::accept, "flaky-engine");
            this.acceptor.setDaemon(true);
        }

        static FlakyEngine start() throws IOException {
            FlakyEngine engine = new FlakyEngine(new ServerSocket(0));
            engine.acceptor.start();
            return engine;
        }

        int port() {
            return socket.getLocalPort();
        }

        private void accept() {
            while (open) {
                try {
                    Socket client = socket.accept();
                    Thread t = new Thread(() -> serve(client), "flaky-engine-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(Socket client) {
            try (client) {
                client.setSoTimeout(IDLE_CLOSE_MS);
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = client.getOutputStream();
                while (true) {
                    String requestLine = in.readLine();
                    if (requestLine == null) {
                        return;
                    }
                    String header;
                    while ((header = in.readLine()) != null && !header.isEmpty()) {
                        // headers are not interesting
                    }
                    Thread.sleep(SLOW_MS);
                    String body = requestLine.contains("/_ping") ? "OK" : "[]";
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + body.length() + "\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
            } catch (SocketTimeoutException idle) {
                // what the proxy does: an idle connection is simply closed
            } catch (IOException | InterruptedException e) {
                // the client went away or the test is over
            }
        }

        @Override
        public void close() throws IOException {
            open = false;
            socket.close();
        }
    }
}
