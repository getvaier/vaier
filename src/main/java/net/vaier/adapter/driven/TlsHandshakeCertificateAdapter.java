package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ConsoleCertificate;
import net.vaier.domain.port.ForInspectingConsoleCertificates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/**
 * Reads the certificate Traefik presents for a host by completing a TLS handshake against it with that host as
 * SNI — from inside the stack, so it sees exactly what a browser would. Trust is deliberately not verified here:
 * the point is to <em>look at</em> the certificate, including Traefik's self-signed placeholder, and the domain
 * decides what it means. Nothing is sent after the handshake.
 */
@Component
@Slf4j
public class TlsHandshakeCertificateAdapter implements ForInspectingConsoleCertificates {

    private final String target;
    private final int port;
    private final int timeoutMs;

    public TlsHandshakeCertificateAdapter(@Value("${vaier.preflight.tls-target:traefik}") String target,
                                          @Value("${vaier.preflight.tls-port:443}") int port,
                                          @Value("${vaier.preflight.tls-timeout-ms:3000}") int timeoutMs) {
        this.target = target;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Optional<ConsoleCertificate> inspect(String host) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] { new LookOnlyTrustManager() }, null);
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress(target, port), timeoutMs);
                socket.setSoTimeout(timeoutMs);
                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(host)));
                socket.setSSLParameters(params);
                socket.startHandshake();
                Certificate[] chain = socket.getSession().getPeerCertificates();
                if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
                    return Optional.empty();
                }
                boolean selfSigned = leaf.getIssuerX500Principal().equals(leaf.getSubjectX500Principal());
                return Optional.of(new ConsoleCertificate(leaf.getIssuerX500Principal().getName(),
                    leaf.getNotAfter().toInstant(), selfSigned));
            }
        } catch (Exception e) {
            log.debug("Could not read the certificate {} presents for {}: {}", target, host, e.toString());
            return Optional.empty();
        }
    }

    /** Accepts any chain: this adapter inspects certificates, it does not rely on them. */
    private static final class LookOnlyTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
