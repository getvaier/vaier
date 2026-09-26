package net.vaier.adapter.driven;

import com.sun.net.httpserver.HttpServer;
import net.vaier.domain.ServiceCall;
import net.vaier.domain.ServiceCallAnswer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpServiceCallAdapterTest {

    private final HttpServiceCallAdapter adapter = new HttpServiceCallAdapter();

    /** The request goes as the call says, and only the status, the type and a bounded body come back. */
    @Test
    void sendsTheMethodBodyAndCredential_neverFollowsARedirect_andReadsABoundedBody() throws Exception {
        List<String> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/items/PoolPump", exchange -> {
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                + exchange.getRequestHeaders().getFirst("Content-Type") + " "
                + exchange.getRequestHeaders().getFirst("Authorization") + " "
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.getResponseHeaders().add("Set-Cookie", "session=SECRET");
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            byte[] body = "a".repeat(HttpServiceCallAdapter.MAX_BYTES + 10).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/elsewhere", exchange -> {
            throw new AssertionError("a service call must not follow a redirect by itself");
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/rest/items/PoolPump?x=1";

            ServiceCallAnswer answer = adapter.call(url, ServiceCall.proposed("POST", "/rest/items/PoolPump", "ON"),
                "Basic dmFpZXI6czNjcmV0");
            adapter.call(url, ServiceCall.read("/rest/items/PoolPump"), null);

            assertThat(seen).containsExactly(
                "POST /rest/items/PoolPump?x=1 text/plain; charset=utf-8 Basic dmFpZXI6czNjcmV0 ON",
                "GET /rest/items/PoolPump?x=1 null null ");
            assertThat(answer.status()).isEqualTo(302);
            assertThat(answer.contentType()).isEqualTo("text/plain");
            assertThat(answer.body()).hasSize(HttpServiceCallAdapter.MAX_BYTES);
            assertThat(answer.more()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aServiceThatDoesNotAnswer_failsWithoutItsOwnWords() {
        assertThatThrownBy(() -> adapter.call("http://127.0.0.1:1/", ServiceCall.read("/rest"), "Basic c2VjcmV0"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("The service did not answer.");
    }
}
