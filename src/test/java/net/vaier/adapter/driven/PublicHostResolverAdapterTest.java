package net.vaier.adapter.driven;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.port.ForResolvingPublicHost.PublicHost;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicHostResolverAdapterTest {

    @Test
    void resolvesCnameFromEnvVaierPublicHost() {
        var env = Map.of("VAIER_PUBLIC_HOST", "server.example.com");
        var adapter = new PublicHostResolverAdapter(env::get, mock(HttpClient.class));

        Optional<PublicHost> result = adapter.resolve();

        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("server.example.com");
        assertThat(result.get().type()).isEqualTo(DnsRecordType.CNAME);
    }

    @Test
    void resolvesARecordFromEnvVaierPublicIp() {
        var env = Map.of("VAIER_PUBLIC_IP", "203.0.113.10");
        var adapter = new PublicHostResolverAdapter(env::get, mock(HttpClient.class));

        Optional<PublicHost> result = adapter.resolve();

        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("203.0.113.10");
        assertThat(result.get().type()).isEqualTo(DnsRecordType.A);
    }

    @Test
    void publicHostTakesPrecedenceOverPublicIp() {
        var env = Map.of(
            "VAIER_PUBLIC_HOST", "server.example.com",
            "VAIER_PUBLIC_IP", "203.0.113.10"
        );
        var adapter = new PublicHostResolverAdapter(env::get, mock(HttpClient.class));

        Optional<PublicHost> result = adapter.resolve();

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(DnsRecordType.CNAME);
    }

    @Test
    void returnsEmptyWhenNoEnvAndImdsUnreachable() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any()))
            .thenThrow(new IOException("no route to host"));
        var adapter = new PublicHostResolverAdapter(k -> null, httpClient);

        assertThat(adapter.resolve()).isEmpty();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resolvesEc2PublicHostnameAsCname() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse tokenResponse = stringResponse(200, "TOKEN_VALUE");
        HttpResponse metaResponse = stringResponse(200, "ec2-1-2-3-4.compute.amazonaws.com");
        when(httpClient.send(argThat(isPutToken()), any())).thenReturn(tokenResponse);
        when(httpClient.send(argThat(isGetPublicHostname()), any())).thenReturn(metaResponse);

        var adapter = new PublicHostResolverAdapter(k -> null, httpClient);
        Optional<PublicHost> result = adapter.resolve();

        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("ec2-1-2-3-4.compute.amazonaws.com");
        assertThat(result.get().type()).isEqualTo(DnsRecordType.CNAME);
    }

    @Test
    void emptyEnvValuesAreTreatedAsAbsent() {
        var env = Map.of("VAIER_PUBLIC_HOST", "   ", "VAIER_PUBLIC_IP", "");
        HttpClient httpClient = mock(HttpClient.class);
        var adapter = new PublicHostResolverAdapter(env::get, httpClient);

        assertThat(adapter.resolve()).isEmpty();
    }

    private static ArgumentMatcher<HttpRequest> isPutToken() {
        return req -> req != null
            && "PUT".equals(req.method())
            && req.uri().getPath().endsWith("/api/token");
    }

    private static ArgumentMatcher<HttpRequest> isGetPublicHostname() {
        return req -> req != null
            && "GET".equals(req.method())
            && req.uri().getPath().endsWith("/meta-data/public-hostname");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> stringResponse(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }
}
