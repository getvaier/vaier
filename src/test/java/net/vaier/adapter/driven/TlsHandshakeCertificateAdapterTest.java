package net.vaier.adapter.driven;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class TlsHandshakeCertificateAdapterTest {

    @Test
    void inspect_aDoorNobodyAnswers_readsAsUnreachable_andNeverThrows() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        TlsHandshakeCertificateAdapter adapter = new TlsHandshakeCertificateAdapter("127.0.0.1", closedPort, 500);

        assertThat(adapter.inspect("vaier.example.com")).isEmpty();
    }
}
