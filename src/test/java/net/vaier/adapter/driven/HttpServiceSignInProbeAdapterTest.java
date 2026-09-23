package net.vaier.adapter.driven;

import com.sun.net.httpserver.HttpServer;
import net.vaier.domain.ServiceProbeAnswer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HttpServiceSignInProbeAdapterTest {

    private final HttpServiceSignInProbeAdapter adapter = new HttpServiceSignInProbeAdapter();

    @Test
    void handsOverWhatTheServiceAnswered_withoutFollowingItsRedirect_andABoundedBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "/admin/");
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"x\"");
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            byte[] body = "a".repeat(200_000).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/admin/", exchange -> {
            throw new AssertionError("the probe must not follow a redirect by itself");
        });
        server.start();
        try {
            ServiceProbeAnswer answer = adapter.probe("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                .orElseThrow();

            assertThat(answer.status()).isEqualTo(302);
            assertThat(answer.location()).isEqualTo("/admin/");
            assertThat(answer.wwwAuthenticate()).isEqualTo("Basic realm=\"x\"");
            assertThat(answer.contentType()).isEqualTo("text/html");
            assertThat(answer.body()).hasSize(HttpServiceSignInProbeAdapter.BODY_LIMIT);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aServiceThatDoesNotAnswerIsNoAnswer() {
        assertThat(adapter.probe("http://127.0.0.1:1/")).isEmpty();
        assertThat(adapter.probe("not a url")).isEmpty();
    }
}
